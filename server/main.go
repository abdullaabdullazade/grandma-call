// Call — minimal family calling server.
// Devices join with group code + name; everyone sees everyone; admin panel at /admin.
package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io/fs"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/gorilla/websocket"
)

//go:embed public
var public embed.FS

var (
	port       = env("PORT", "8080")
	adminPass  = os.Getenv("ADMIN_PASSWORD")
	turnSecret = os.Getenv("TURN_SECRET") // coturn static-auth-secret
	turnURLs   = strings.FieldsFunc(os.Getenv("TURN_URLS"), func(r rune) bool { return r == ',' })
	dbPath     = env("DB", "data/db.json")
	adminPath  = os.Getenv("ADMIN_PATH") // secret URL segment for the admin panel
	dlPath     = os.Getenv("DL_PATH")    // secret URL segment for the public APK link
	appDir     = filepath.Join(filepath.Dir(env("DB", "data/db.json")), "app")
	handler    http.Handler
)

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

type user struct {
	Name       string `json:"name"`
	Token      string `json:"token"`
	Device     string `json:"device"`   // model / user agent
	DeviceID   string `json:"deviceId"` // ANDROID_ID or browser-generated id
	IP         string `json:"ip"`
	Joined     int64  `json:"joined"`
	LastSeen   int64  `json:"lastSeen"`
	Banned     bool   `json:"banned"`
	Group      string `json:"group"` // group id; members only see and call each other
	Version    string `json:"version"`
	Push       string `json:"push,omitempty"`       // FCM registration token
	MissedSeen int64  `json:"missedSeen,omitempty"` // missed calls up to here were notified
}

// group is an isolated circle of contacts with its own join code.
type group struct {
	Name    string `json:"name"`
	Code    string `json:"code"`
	Created int64  `json:"created"`
}

// callLog is one finished call. Status: answered, missed, declined, busy, unavailable.
type callLog struct {
	ID       string `json:"id"`
	From     string `json:"from"`
	To       string `json:"to"`
	Video    bool   `json:"video"`
	Started  int64  `json:"started"`
	Duration int64  `json:"duration"` // seconds of talk, 0 unless answered
	Status   string `json:"status"`
	Logged   int64  `json:"logged,omitempty"` // when the leg was written (missed-call delivery)
}

const maxLog = 5000

var db struct {
	JoinCode string            `json:"joinCode,omitempty"` // legacy single code, migrated into a group
	Groups   map[string]*group `json:"groups"`
	Users    map[string]*user  `json:"users"`
	Calls    []callLog         `json:"calls"`
}

type conn struct {
	ws  *websocket.Conn
	uid string
	out chan []byte
	ack bool // app sends ring_ack (v4.2+)
}

var (
	mu       sync.Mutex
	conns    = map[string]map[*conn]bool{}
	fails    = map[string]int{}
	sessions = map[string]time.Time{} // admin session -> expiry
)

func randHex(n int) string { b := make([]byte, n); rand.Read(b); return hex.EncodeToString(b) }
func now() int64           { return time.Now().Unix() }
func eq(a, b string) bool  { return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }

func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	for utf8.RuneCountInString(s) > n {
		_, sz := utf8.DecodeLastRuneInString(s)
		s = s[:len(s)-sz]
	}
	return s
}

// caller holds mu
// save writes atomically and durably (fsync before rename); the previous copy is kept as .bak.
func save() {
	os.MkdirAll(filepath.Dir(dbPath), 0o700)
	b, _ := json.Marshal(db)
	f, err := os.OpenFile(dbPath+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		log.Print("save: ", err)
		return
	}
	_, err = f.Write(b)
	if err == nil {
		err = f.Sync()
	}
	f.Close()
	if err != nil {
		log.Print("save: ", err)
		return
	}
	// db.json is never missing: hard-link the current copy as .bak, then atomically replace it
	os.Remove(dbPath + ".bak")
	os.Link(dbPath, dbPath+".bak")
	os.Rename(dbPath+".tmp", dbPath)
}

func (c *conn) send(m any) {
	if c == nil { // leg temporarily disconnected
		return
	}
	b, _ := json.Marshal(m)
	select {
	case c.out <- b:
	default: // client can't keep up: drop the connection rather than silently lose messages
		c.ws.Close()
	}
}

func online(id string) bool { return len(conns[id]) > 0 }

func deviceBanned(did string) bool {
	if did == "" {
		return false
	}
	for _, u := range db.Users {
		if u.Banned && u.DeviceID == did {
			return true
		}
	}
	return false
}

// caller holds mu
func broadcastContacts() {
	type contact struct {
		ID       string `json:"id"`
		Name     string `json:"name"`
		Online   bool   `json:"online"`
		LastSeen int64  `json:"lastSeen"`
	}
	lists := map[string][]contact{}
	for id, u := range db.Users {
		if !u.Banned {
			lists[u.Group] = append(lists[u.Group], contact{id, u.Name, online(id), u.LastSeen})
		}
	}
	for uid, set := range conns {
		u := db.Users[uid]
		if u == nil {
			continue
		}
		list := lists[u.Group]
		if list == nil {
			list = []contact{}
		}
		for c := range set {
			c.send(map[string]any{"type": "contacts", "list": list})
		}
	}
}

func nameOf(id string) string {
	if u := db.Users[id]; u != nil {
		return u.Name
	}
	return "?"
}

// history returns the latest 100 calls involving uid, newest first. caller holds mu.
// callGroup is one call as people remember it: every leg logged for the same room. Newest first.
type callGroup struct {
	legs   []callLog
	owner  string   // who started it
	people []string // owner first, then invitees in order
}

// groupedCalls folds per-invitee legs ("room:uid" ids) back into calls. caller holds mu.
func groupedCalls(limit int) []*callGroup {
	idx := map[string]*callGroup{}
	out := []*callGroup{}
	for i := len(db.Calls) - 1; i >= 0 && len(out) <= limit; i-- {
		e := db.Calls[i]
		rid := e.ID
		if k := strings.LastIndex(e.ID, ":"); k > 0 {
			rid = e.ID[:k]
		}
		g := idx[rid]
		if g == nil {
			g = &callGroup{}
			idx[rid] = g
			out = append(out, g)
		}
		g.legs = append(g.legs, e)
	}
	for _, g := range out {
		sort.Slice(g.legs, func(a, b int) bool { return g.legs[a].Started < g.legs[b].Started })
		invited := map[string]bool{}
		for _, l := range g.legs {
			invited[l.To] = true
		}
		seen := map[string]bool{}
		add := func(id string) {
			if !seen[id] {
				seen[id] = true
				g.people = append(g.people, id)
			}
		}
		for _, l := range g.legs {
			if !invited[l.From] && g.owner == "" {
				g.owner = l.From
			}
		}
		if g.owner == "" {
			g.owner = g.legs[0].From
		}
		add(g.owner)
		for _, l := range g.legs {
			add(l.From)
			add(l.To)
		}
	}
	if len(out) > limit {
		out = out[:limit]
	}
	return out
}

// summary: overall status and the longest talk time of a call.
func (g *callGroup) summary() (status string, duration int64, started int64, video bool) {
	status, started, video = g.legs[0].Status, g.legs[0].Started, g.legs[0].Video
	for _, l := range g.legs {
		if l.Status == "answered" {
			status = "answered"
		}
		if l.Duration > duration {
			duration = l.Duration
		}
	}
	return
}

func history(uid string) []map[string]any {
	out := []map[string]any{}
	for _, g := range groupedCalls(400) {
		var mine *callLog
		in := false
		for i := range g.legs {
			if g.legs[i].To == uid {
				mine, in = &g.legs[i], true
			}
		}
		others, names := []string{}, []string{}
		member := false
		for _, id := range g.people {
			if id == uid {
				member = true
				continue
			}
			others = append(others, id)
			names = append(names, nameOf(id))
		}
		if !member || len(others) == 0 {
			continue
		}
		status, duration, started, video := g.summary()
		dir := "out"
		if in {
			dir, status, duration, started = "in", mine.Status, mine.Duration, mine.Started
		}
		out = append(out, map[string]any{"id": g.legs[0].ID, "peer": others[0], "name": strings.Join(names, ", "),
			"members": others, "group": len(others) > 1, "dir": dir, "video": video,
			"started": started, "duration": duration, "status": status})
		if len(out) >= 100 {
			break
		}
	}
	return out
}

// historyMsg bundles recent calls with total talk seconds per peer. caller holds mu.
func historyMsg(uid string) map[string]any {
	totals := map[string]int64{}
	for _, e := range db.Calls {
		if e.From == uid {
			totals[e.To] += e.Duration
		} else if e.To == uid {
			totals[e.From] += e.Duration
		}
	}
	return map[string]any{"type": "history", "list": history(uid), "totals": totals}
}

// caller holds mu
func pushHistory(uid string) {
	h := historyMsg(uid)
	for c := range conns[uid] {
		c.send(h)
	}
}

// caller holds mu
func kick(uid string) {
	for c := range conns[uid] {
		c.send(map[string]any{"type": "banned"})
		c.ws.Close()
	}
}

func iceServers(id string) []map[string]any {
	if turnSecret == "" || len(turnURLs) == 0 {
		return []map[string]any{{"urls": "stun:stun.l.google.com:19302"}}
	}
	username := fmt.Sprintf("%d:%s", now()+86400, id)
	h := hmac.New(sha1.New, []byte(turnSecret))
	h.Write([]byte(username))
	return []map[string]any{{"urls": turnURLs, "username": username, "credential": base64.StdEncoding.EncodeToString(h.Sum(nil))}}
}

func clientIP(r *http.Request) string {
	if f := r.Header.Get("X-Forwarded-For"); f != "" {
		return strings.TrimSpace(strings.Split(f, ",")[0])
	}
	h, _, _ := net.SplitHostPort(r.RemoteAddr)
	return h
}

// throttle guards brute force on join code and admin password; caller must not hold mu
// attempt counts a try before the credentials are checked, so parallel guesses can't slip past
// the limit; success gives it back. Join and admin login have separate counters. caller holds mu.
func attempt(kind, ip string) bool {
	k := kind + " " + ip
	if fails[k] >= 10 {
		return false
	}
	fails[k]++
	return true
}

func succeeded(kind, ip string) { fails[kind+" "+ip]-- }

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

// --- device join ---

// groupByCode checks every group in constant time per code. caller holds mu.
func groupByCode(code string) string {
	found := ""
	for id, g := range db.Groups {
		if code != "" && eq(code, g.Code) {
			found = id
		}
	}
	return found
}

func codeTaken(code, except string) bool {
	for id, g := range db.Groups {
		if id != except && strings.EqualFold(g.Code, code) {
			return true
		}
	}
	return false
}

func newCode() string {
	for {
		b := make([]byte, 4)
		rand.Read(b)
		c := fmt.Sprintf("%06d", (uint32(b[0])<<24|uint32(b[1])<<16|uint32(b[2])<<8|uint32(b[3]))%900000+100000)
		if !codeTaken(c, "") {
			return c
		}
	}
}

func join(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.WriteHeader(405)
		return
	}
	ip := clientIP(r)
	var j struct{ Name, Code, Device, DeviceID string }
	json.NewDecoder(http.MaxBytesReader(w, r.Body, 2000)).Decode(&j)
	name := clip(j.Name, 30)
	mu.Lock()
	defer mu.Unlock()
	if !attempt("join", ip) {
		w.WriteHeader(429)
		return
	}
	gid := groupByCode(strings.TrimSpace(j.Code))
	if name == "" || gid == "" {
		w.WriteHeader(403) // the attempt stays counted
		return
	}
	succeeded("join", ip)
	did := clip(j.DeviceID, 64)
	if deviceBanned(did) {
		w.WriteHeader(451)
		return
	}
	// The same phone always gets its existing account back: no duplicates, history kept.
	if did != "" {
		for _, u := range db.Users {
			if u.DeviceID == did && u.Group == gid { // same phone, same group: give its account back
				u.Name, u.IP = name, ip
				if d := clip(j.Device, 200); d != "" {
					u.Device = d
				}
				save()
				broadcastContacts()
				writeJSON(w, map[string]string{"token": u.Token})
				return
			}
		}
	}
	b := make([]byte, 24)
	rand.Read(b)
	token := base64.RawURLEncoding.EncodeToString(b)
	db.Users[randHex(6)] = &user{Name: name, Token: token, Device: clip(j.Device, 200), DeviceID: did, IP: ip, Joined: now(), LastSeen: now(), Group: gid}
	save()
	broadcastContacts()
	writeJSON(w, map[string]string{"token": token})
}

// --- admin ---

func isAdmin(r *http.Request) bool {
	c, err := r.Cookie("zeng_admin")
	if err != nil {
		return false
	}
	mu.Lock()
	defer mu.Unlock()
	exp, ok := sessions[c.Value]
	return ok && time.Now().Before(exp)
}

func adminLogin(w http.ResponseWriter, r *http.Request) {
	ip := clientIP(r)
	if r.Method != http.MethodPost {
		w.WriteHeader(405)
		return
	}
	var j struct{ Password string }
	json.NewDecoder(http.MaxBytesReader(w, r.Body, 1000)).Decode(&j)
	mu.Lock()
	defer mu.Unlock()
	if !attempt("admin", ip) {
		w.WriteHeader(429)
		return
	}
	if adminPass == "" || !eq(j.Password, adminPass) {
		w.WriteHeader(403)
		return
	}
	succeeded("admin", ip)
	sid := randHex(32)
	sessions[sid] = time.Now().Add(30 * 24 * time.Hour)
	http.SetCookie(w, &http.Cookie{Name: "zeng_admin", Value: sid, Path: "/" + adminPath + "/", HttpOnly: true, SameSite: http.SameSiteStrictMode,
		Secure: r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https", MaxAge: 30 * 24 * 3600})
	w.WriteHeader(204)
}

func adminAPI(w http.ResponseWriter, r *http.Request) {
	if !isAdmin(r) {
		w.WriteHeader(401)
		return
	}
	if r.Method == http.MethodGet {
		type row struct {
			ID string `json:"id"`
			user
			Online bool  `json:"online"`
			Talk   int64 `json:"talk"`
		}
		mu.Lock()
		rows := []row{}
		for id, u := range db.Users {
			x := *u
			x.Token, x.Push = "", ""
			rows = append(rows, row{ID: id, user: x, Online: online(id)})
		}
		type grow struct {
			ID string `json:"id"`
			group
			Members int `json:"members"`
			Online  int `json:"online"`
		}
		groups := []grow{}
		for id, g := range db.Groups {
			gr := grow{ID: id, group: *g}
			for uid, u := range db.Users {
				if u.Group == id {
					gr.Members++
					if online(uid) {
						gr.Online++
					}
				}
			}
			groups = append(groups, gr)
		}
		sort.Slice(groups, func(i, k int) bool { return groups[i].Created < groups[k].Created })
		talk := map[string]int64{} // total talk seconds per user
		for _, e := range db.Calls {
			talk[e.From] += e.Duration
			talk[e.To] += e.Duration
		}
		for i := range rows {
			rows[i].Talk = talk[rows[i].ID]
		}
		calls := []map[string]any{}
		for _, g := range groupedCalls(300) {
			status, duration, started, video := g.summary()
			to := []string{}
			for _, id := range g.people[1:] {
				to = append(to, nameOf(id))
			}
			calls = append(calls, map[string]any{"from": nameOf(g.owner), "to": strings.Join(to, ", "), "group": len(to) > 1,
				"video": video, "started": started, "duration": duration, "status": status})
		}
		mu.Unlock()
		sort.Slice(rows, func(i, k int) bool { return rows[i].Joined > rows[k].Joined })
		app, _ := loadMeta()
		dl := ""
		if dlPath != "" {
			dl = "/d/" + dlPath + "/call.apk"
		}
		tone, _ := loadTone()
		writeJSON(w, map[string]any{"groups": groups, "users": rows, "calls": calls, "app": app, "download": dl, "ringtone": tone})
		return
	}
	if r.Method != http.MethodPost {
		w.WriteHeader(405)
		return
	}
	var j struct{ Action, ID, Value string }
	json.NewDecoder(http.MaxBytesReader(w, r.Body, 1000)).Decode(&j)
	mu.Lock()
	defer mu.Unlock()
	u := db.Users[j.ID]
	switch j.Action {
	case "group_create":
		name := clip(j.Value, 40)
		if name == "" {
			http.Error(w, "qrup adı boşdur", 400)
			return
		}
		db.Groups["g"+randHex(5)] = &group{Name: name, Code: newCode(), Created: now()}
	case "group_rename", "group_code", "group_delete":
		g := db.Groups[j.ID]
		if g == nil {
			w.WriteHeader(404)
			return
		}
		switch j.Action {
		case "group_rename":
			if clip(j.Value, 40) == "" {
				w.WriteHeader(400)
				return
			}
			g.Name = clip(j.Value, 40)
		case "group_code":
			c := strings.TrimSpace(clip(j.Value, 64))
			if c == "" {
				c = newCode()
			}
			if len(c) < 4 {
				http.Error(w, "kod ən azı 4 simvol olmalıdır", 400)
				return
			}
			if codeTaken(c, j.ID) {
				http.Error(w, "bu kod başqa qrupdadır", 400)
				return
			}
			g.Code = c
		case "group_delete":
			for _, x := range db.Users {
				if x.Group == j.ID {
					http.Error(w, "qrupda cihazlar var, əvvəlcə onları köçürün və ya silin", 400)
					return
				}
			}
			delete(db.Groups, j.ID)
		}
	case "move":
		if u == nil || db.Groups[j.Value] == nil {
			w.WriteHeader(404)
			return
		}
		u.Group = j.Value
		broadcastContacts() // both groups get fresh contact lists
	case "ban", "unban":
		if u == nil {
			w.WriteHeader(404)
			return
		}
		u.Banned = j.Action == "ban"
		if u.Banned {
			kick(j.ID)
		}
		broadcastContacts()
	case "rename":
		if u == nil || clip(j.Value, 30) == "" {
			w.WriteHeader(400)
			return
		}
		u.Name = clip(j.Value, 30)
		broadcastContacts()
	case "delete":
		if u == nil {
			w.WriteHeader(404)
			return
		}
		kick(j.ID)
		delete(db.Users, j.ID)
		broadcastContacts()
	default:
		w.WriteHeader(400)
		return
	}
	save()
	w.WriteHeader(204)
}

// --- signaling ---

var upgrader = websocket.Upgrader{ReadBufferSize: 4096, WriteBufferSize: 4096}

func wsHandler(w http.ResponseWriter, r *http.Request) {
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	c := &conn{ws: ws, out: make(chan []byte, 256)}
	go writer(c)
	reader(c, clientIP(r))
}

func writer(c *conn) {
	ping := time.NewTicker(15 * time.Second)
	defer func() { ping.Stop(); c.ws.Close() }()
	for {
		select {
		case b, ok := <-c.out:
			c.ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if !ok {
				c.ws.WriteMessage(websocket.CloseMessage, nil)
				return
			}
			if c.ws.WriteMessage(websocket.TextMessage, b) != nil {
				return
			}
		case <-ping.C:
			c.ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if c.ws.WriteMessage(websocket.PingMessage, nil) != nil {
				return
			}
		}
	}
}

type msg struct {
	Type    string          `json:"type"`
	Token   string          `json:"token"`
	To      string          `json:"to"`
	CallID  string          `json:"callId"`
	Video   bool            `json:"video"`
	Version string          `json:"version"`
	Msg     string          `json:"msg"`
	Data    json.RawMessage `json:"data"`
}

func reader(c *conn, ip string) {
	defer func() {
		mu.Lock()
		if c.uid != "" {
			delete(conns[c.uid], c)
			if u := db.Users[c.uid]; u != nil {
				u.LastSeen = now()
				save()
			}
			dropped(c)
			broadcastContacts()
		}
		mu.Unlock()
		close(c.out)
	}()
	c.ws.SetReadLimit(64 * 1024)
	c.ws.SetReadDeadline(time.Now().Add(40 * time.Second))
	c.ws.SetPongHandler(func(string) error { return c.ws.SetReadDeadline(time.Now().Add(40 * time.Second)) })

	for {
		var m msg
		if c.ws.ReadJSON(&m) != nil {
			return
		}
		mu.Lock()
		handle(c, &m, ip)
		mu.Unlock()
		if c.uid == "" {
			return
		}
	}
}

// caller holds mu
func handle(c *conn, m *msg, ip string) {
	if c.uid == "" {
		if m.Type != "auth" {
			return
		}
		for id, u := range db.Users {
			if eq(m.Token, u.Token) {
				if u.Banned {
					c.send(map[string]any{"type": "banned"})
					return
				}
				c.uid = id
			}
		}
		if c.uid == "" {
			c.send(map[string]any{"type": "denied"})
			return
		}
		u := db.Users[c.uid]
		// missed calls this phone hasn't been told about yet (it was asleep or offline)
		n := 0
		for i := len(db.Calls) - 1; i >= 0 && n < 10; i-- {
			e := db.Calls[i]
			if e.To == c.uid && e.Status == "missed" && e.Logged > u.MissedSeen {
				c.send(map[string]any{"type": "missed", "from": e.From, "name": nameOf(e.From), "video": e.Video})
				n++
			}
		}
		u.MissedSeen = now()
		u.IP, u.LastSeen = ip, now()
		if m.Version != "" {
			u.Version = clip(m.Version, 20)
			c.ack = true
		}
		if conns[c.uid] == nil {
			conns[c.uid] = map[*conn]bool{}
		}
		conns[c.uid][c] = true
		c.send(map[string]any{"type": "hello", "me": map[string]string{"id": c.uid, "name": u.Name}, "ice": iceServers(c.uid), "ringtone": toneInfo()})
		c.send(historyMsg(c.uid))
		onAuth(c)
		broadcastContacts()
		return
	}

	if m.Type == "ping" { // phone heartbeat: proves the socket is alive end to end
		c.send(map[string]any{"type": "pong"})
		return
	}
	if m.Type == "idle" { // app restarted and isn't in any call: drop stale memberships now
		for _, r := range rooms {
			if om := r.members[c.uid]; om != nil && om.joined && om.conn != c {
				r.leave(c.uid, "answered", "lost")
			}
		}
		return
	}
	if m.Type == "diag" { // app-side diagnostics (e.g. why an update didn't install)
		log.Printf("diag %s: %s", nameOf(c.uid), clip(m.Msg, 300))
		return
	}
	if m.Type == "push_token" {
		if u := db.Users[c.uid]; u != nil && len(m.Token) < 400 && u.Push != m.Token {
			u.Push = m.Token
			save()
		}
		return
	}
	if m.Type == "history" {
		c.send(historyMsg(c.uid))
		return
	}
	handleCall(c, m)
}

func main() {
	if adminPass == "" || len(adminPath) < 16 {
		log.Fatal("ADMIN_PASSWORD and ADMIN_PATH (>=16 chars) env required")
	}
	loadFCM()
	db.Users = map[string]*user{}
	// first readable of db.json, a finished .tmp, then .bak; refuse to start empty if files exist but are broken
	loaded, seen := false, false
	for _, p := range []string{dbPath, dbPath + ".tmp", dbPath + ".bak"} {
		b, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		seen = true
		if json.Unmarshal(b, &db) == nil {
			if p != dbPath {
				log.Print("db: recovered from ", p)
			}
			loaded = true
			break
		}
		db.Users, db.Groups, db.Calls = nil, nil, nil
	}
	if seen && !loaded {
		log.Fatal("db: all copies unreadable, refusing to start with an empty database")
	}
	if db.Users == nil {
		db.Users = map[string]*user{}
	}
	if db.Groups == nil {
		db.Groups = map[string]*group{}
	}
	if len(db.Groups) == 0 { // migrate the single family code into a first group
		code := db.JoinCode
		if code == "" {
			code = env("JOIN_CODE", newCode())
		}
		db.Groups["gdefault"] = &group{Name: "Ailə", Code: code, Created: now()}
	}
	db.JoinCode = ""
	for _, u := range db.Users {
		if db.Groups[u.Group] == nil {
			for id := range db.Groups {
				u.Group = id
				break
			}
		}
	}
	save()
	go func() {
		for range time.Tick(15 * time.Minute) {
			mu.Lock()
			fails = map[string]int{}
			for s, exp := range sessions {
				if time.Now().After(exp) {
					delete(sessions, s)
				}
			}
			mu.Unlock()
		}
	}()
	static, _ := fs.Sub(public, "public")
	adm := "/" + adminPath + "/"
	mux := http.NewServeMux()
	// app endpoints
	mux.HandleFunc("/join", join)
	mux.HandleFunc("/ws", wsHandler)
	mux.HandleFunc("/app/update", appUpdate)
	mux.HandleFunc("/app/apk", appAPK)
	mux.HandleFunc("/app/ringtone", appRingtone)
	mux.HandleFunc("/app/crash", appCrash)
	if dlPath != "" { // public install link handed out by the admin
		mux.HandleFunc("/d/"+dlPath+"/call.apk", serveAPK)
	}
	// admin: only reachable under the secret path
	mux.HandleFunc(adm, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != adm {
			http.NotFound(w, r)
			return
		}
		http.ServeFileFS(w, r, static, "admin.html")
	})
	mux.Handle(adm+"icon.png", http.StripPrefix(adm, http.FileServer(http.FS(static))))
	mux.HandleFunc(adm+"api", adminAPI)
	mux.HandleFunc(adm+"api/login", adminLogin)
	mux.HandleFunc(adm+"api/upload", adminUpload)
	mux.HandleFunc(adm+"api/ringtone", adminRingtone)
	mux.HandleFunc(adm+"api/logout", func(w http.ResponseWriter, r *http.Request) {
		if c, err := r.Cookie("zeng_admin"); err == nil {
			mu.Lock()
			delete(sessions, c.Value)
			mu.Unlock()
		}
		http.SetCookie(w, &http.Cookie{Name: "zeng_admin", Value: "", Path: adm, MaxAge: -1})
		w.WriteHeader(204)
	})
	// everything else looks like nothing is here
	mux.HandleFunc("/", http.NotFound)
	handler = secure(mux, adm)
	log.Println("call server on :" + port)
	srv := &http.Server{Addr: ":" + port, Handler: handler, ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.ListenAndServe())
}

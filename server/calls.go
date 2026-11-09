package main

// Calls are rooms: the caller joins immediately, invitees ring until they accept, decline or time out.
// Media is a WebRTC mesh between joined members; the server only relays signaling.
//
// Client -> server: call{to,callId,video} invite{callId,to} accept decline busy hangup resume{callId}
//                   signal{callId,to,data}
// Server -> client: incoming{callId,from,name,video,members} ringing{callId,peer}
//                   joined{callId,peers} peer_joined{callId,peer,name} peer_left{callId,peer,reason}
//                   signal{callId,from,data} resumed{callId,peer} ended{callId,reason}

import (
	"log"
	"time"
)

const (
	maxMembers  = 4 // mesh: every phone sends to every other one, keep it small for old phones
	ackTimeout  = 4 * time.Second
	ringTimeout = 45 * time.Second
	graceTime   = 60 * time.Second
)

type member struct {
	uid     string
	conn    *conn  // device in the call; nil while ringing or reconnecting
	joined  bool   // answered (or the caller)
	rang    bool   // a device confirmed (ring_ack) that it is ringing
	inviter string // who rang this member
	invited time.Time
	since   time.Time   // when the member joined
	timer   *time.Timer // ring timeout, or reconnect grace
}

type room struct {
	id      string
	owner   string
	video   bool
	members map[string]*member
}

var rooms = map[string]*room{}

func (r *room) count(joined bool) int {
	n := 0
	for _, m := range r.members {
		if m.joined == joined {
			n++
		}
	}
	return n
}

// tell every joined member except one. caller holds mu.
func (r *room) broadcast(msg map[string]any, except string) {
	for uid, m := range r.members {
		if uid != except && m.joined {
			m.conn.send(msg)
		}
	}
}

func (r *room) names() []string {
	out := []string{}
	for uid, m := range r.members {
		if m.joined {
			out = append(out, nameOf(uid))
		}
	}
	return out
}

// busy: the user is already talking (or calling) in some room. caller holds mu.
func busy(uid string) bool {
	for _, r := range rooms {
		if m := r.members[uid]; m != nil && m.joined {
			return true
		}
	}
	return false
}

func (r *room) incomingMsg(m *member) map[string]any {
	return map[string]any{"type": "incoming", "callId": r.id, "from": m.inviter, "name": nameOf(m.inviter),
		"video": r.video, "members": r.names()}
}

// ring delivers the incoming call to every online device of the invitee. A device that doesn't
// confirm within ackTimeout has a dead socket (the server just didn't notice yet): it is closed so
// the phone reconnects, and onAuth rings it again. caller holds mu.
func (r *room) ring(m *member) {
	sent := []*conn{}
	for c := range conns[m.uid] {
		c.send(r.incomingMsg(m))
		if c.ack {
			sent = append(sent, c)
		} else { // older app without ring_ack: assume it rings
			r.ringAck(c)
		}
	}
	log.Printf("call %s: ring %s on %d device(s)", short(r.id), nameOf(m.uid), len(conns[m.uid]))
	if len(conns[m.uid]) == 0 { // phone asleep or offline: wake it, it rings once it reconnects
		pushWake(m.uid, r.id, m.inviter)
	}
	if len(sent) == 0 {
		return
	}
	id, uid := r.id, m.uid
	time.AfterFunc(ackTimeout, func() {
		mu.Lock()
		defer mu.Unlock()
		x := rooms[id]
		if x == nil || x.members[uid] == nil || x.members[uid].rang || x.members[uid].joined {
			return
		}
		for _, c := range sent {
			if conns[uid][c] {
				log.Printf("call %s: no ring_ack from %s, closing stale connection", short(id), nameOf(uid))
				c.ws.Close()
			}
		}
		pushWake(uid, id, x.members[uid].inviter)
	})
}

// ringAck: the invitee's phone is really ringing; now the caller may show "Ringing…".
func (r *room) ringAck(c *conn) {
	m := r.members[c.uid]
	if m == nil || m.joined || m.rang {
		return
	}
	m.rang = true
	log.Printf("call %s: %s is ringing", short(r.id), nameOf(c.uid))
	if inv := r.members[m.inviter]; inv != nil {
		inv.conn.send(map[string]any{"type": "ringing", "callId": r.id, "peer": c.uid})
	}
}

func short(id string) string {
	if len(id) > 8 {
		return id[:8]
	}
	return id
}

// invite adds a ringing member. Offline invitees ring as soon as they reconnect. caller holds mu.
// invite reports false when the target can't be called at all (unknown, banned, other group).
func (r *room) invite(from, to string) bool {
	u, me := db.Users[to], db.Users[from]
	if u == nil || me == nil || u.Banned || u.Group != me.Group || r.members[to] != nil || len(r.members) >= maxMembers {
		return false
	}
	m := &member{uid: to, inviter: from, invited: time.Now()}
	r.members[to] = m
	if busy(to) {
		r.leave(to, "busy", "busy")
		return true
	}
	id := r.id
	m.timer = time.AfterFunc(ringTimeout, func() {
		mu.Lock()
		defer mu.Unlock()
		if x := rooms[id]; x != nil && x.members[to] == m && !m.joined {
			x.leave(to, "missed", "no_answer")
		}
	})
	r.ring(m)
	return true
}

func (r *room) accept(c *conn) {
	m := r.members[c.uid]
	if m == nil || m.joined {
		return
	}
	m.joined, m.conn, m.since = true, c, time.Now()
	m.timer.Stop()
	for d := range conns[c.uid] {
		if d != c {
			d.send(map[string]any{"type": "ended", "callId": r.id, "reason": "answered_elsewhere"})
		}
	}
	// The newcomer offers to everyone already in the call.
	peers := []map[string]string{}
	for uid, o := range r.members {
		if uid != c.uid && o.joined {
			peers = append(peers, map[string]string{"id": uid, "name": nameOf(uid)})
		}
	}
	c.send(map[string]any{"type": "joined", "callId": r.id, "peers": peers})
	r.broadcast(map[string]any{"type": "peer_joined", "callId": r.id, "peer": c.uid, "name": nameOf(c.uid)}, c.uid)
}

// leave removes a member, logs their leg and ends the room when nobody is left to talk to.
// status is the log status for an unanswered leg; reason is shown to the others. caller holds mu.
func (r *room) leave(uid, status, reason string) {
	m := r.members[uid]
	if m == nil {
		return
	}
	delete(r.members, uid)
	if m.timer != nil {
		m.timer.Stop()
	}
	log.Printf("call %s: %s left (%s)", short(r.id), nameOf(uid), reason)
	if !m.joined { // stop the ringing on all of the invitee's devices
		for c := range conns[uid] {
			c.send(map[string]any{"type": "ended", "callId": r.id, "reason": reason})
		}
	}
	r.logLeg(m, status)
	r.broadcast(map[string]any{"type": "peer_left", "callId": r.id, "peer": uid, "reason": reason}, uid)

	// keep going while two people talk, or one waits for someone still ringing
	joined, ringing := r.count(true), r.count(false)
	if joined >= 2 || (joined == 1 && ringing >= 1) {
		return
	}
	delete(rooms, r.id)
	rest := r.members
	r.members = map[string]*member{}
	for _, o := range rest {
		if o.timer != nil {
			o.timer.Stop()
		}
		if o.joined {
			o.conn.send(map[string]any{"type": "ended", "callId": r.id, "reason": reason})
		} else {
			for c := range conns[o.uid] {
				c.send(map[string]any{"type": "ended", "callId": r.id, "reason": "hangup"})
			}
		}
		r.logLeg(o, "missed")
	}
}

// logLeg records one invitee's part of the call; the room creator has no leg of their own.
func (r *room) logLeg(m *member, status string) {
	if m.inviter == "" {
		return
	}
	e := callLog{ID: r.id + ":" + m.uid, From: m.inviter, To: m.uid, Video: r.video, Started: m.invited.Unix(), Status: status, Logged: now()}
	if m.joined {
		e.Status = "answered"
		e.Duration = int64(time.Since(m.since).Seconds())
	}
	db.Calls = append(db.Calls, e)
	if len(db.Calls) > maxLog {
		db.Calls = db.Calls[len(db.Calls)-maxLog:]
	}
	save()
	if e.Status == "missed" {
		for c := range conns[m.uid] {
			c.send(map[string]any{"type": "missed", "from": m.inviter, "name": nameOf(m.inviter), "video": r.video})
		}
	}
	pushHistory(m.inviter)
	pushHistory(m.uid)
}

// dropped: a device's socket closed. Joined members get a grace period to resume. caller holds mu.
func dropped(c *conn) {
	for _, r := range rooms {
		m := r.members[c.uid]
		if m == nil || m.conn != c {
			continue
		}
		if r.count(true) == 1 { // nobody answered yet: the caller is gone, stop ringing everyone
			r.leave(c.uid, "missed", "lost")
			continue
		}
		m.conn = nil
		id, uid, mm := r.id, c.uid, m
		m.timer = time.AfterFunc(graceTime, func() {
			mu.Lock()
			defer mu.Unlock()
			if x := rooms[id]; x != nil && x.members[uid] == mm && mm.conn == nil {
				x.leave(uid, "answered", "lost")
			}
		})
	}
}

// onAuth: ring calls waiting for this user (they were offline when invited). caller holds mu.
func onAuth(c *conn) {
	for _, r := range rooms {
		if m := r.members[c.uid]; m != nil && !m.joined {
			c.send(r.incomingMsg(m))
			log.Printf("call %s: %s reconnected, ringing again", short(r.id), nameOf(c.uid))
		}
	}
}

// handleCall processes call-related messages from an authenticated device. caller holds mu.
func handleCall(c *conn, m *msg) {
	if m.Type == "call" {
		if m.CallID == "" || len(m.CallID) > 64 || rooms[m.CallID] != nil || m.To == c.uid {
			return
		}
		// a room this user is still "in" from a dropped socket would make them look busy forever
		for _, old := range rooms {
			if om := old.members[c.uid]; om != nil && om.joined && om.conn != c {
				old.leave(c.uid, "answered", "lost")
			}
		}
		if busy(c.uid) {
			c.send(map[string]any{"type": "ended", "callId": m.CallID, "reason": "busy"})
			return
		}
		r := &room{id: m.CallID, owner: c.uid, video: m.Video, members: map[string]*member{}}
		r.members[c.uid] = &member{uid: c.uid, conn: c, joined: true, since: time.Now()}
		rooms[r.id] = r
		log.Printf("call %s: %s calls %s (video=%v)", short(r.id), nameOf(c.uid), nameOf(m.To), m.Video)
		if !r.invite(c.uid, m.To) {
			delete(rooms, r.id)
			c.send(map[string]any{"type": "ended", "callId": r.id, "reason": "unavailable"})
		}
		return
	}

	r := rooms[m.CallID]
	if r == nil {
		if m.Type == "resume" {
			c.send(map[string]any{"type": "ended", "callId": m.CallID, "reason": "lost"})
		}
		return
	}
	me := r.members[c.uid]
	if me == nil {
		return
	}
	active := me.joined && me.conn == c
	switch m.Type {
	case "invite":
		if active {
			r.invite(c.uid, m.To)
		}
	case "ring_ack":
		r.ringAck(c)
	case "accept":
		log.Printf("call %s: %s answered", short(r.id), nameOf(c.uid))
		r.accept(c)
	case "decline":
		if !me.joined {
			r.leave(c.uid, "declined", "declined")
		}
	case "busy":
		if !me.joined {
			r.leave(c.uid, "busy", "busy")
		}
	case "hangup":
		if !me.joined {
			r.leave(c.uid, "declined", "declined")
		} else if active {
			r.leave(c.uid, "answered", "hangup")
		}
	case "signal":
		if to := r.members[m.To]; active && to != nil && to.joined {
			to.conn.send(map[string]any{"type": "signal", "callId": r.id, "from": c.uid, "data": m.Data})
		}
	case "resume":
		if me.joined && me.conn != c { // new socket replaces a dead (or not yet noticed dead) one
			log.Printf("call %s: %s resumed", short(r.id), nameOf(c.uid))
			me.conn = c
			if me.timer != nil {
				me.timer.Stop()
			}
			r.broadcast(map[string]any{"type": "resumed", "callId": r.id, "peer": c.uid}, c.uid)
		}
	}
}

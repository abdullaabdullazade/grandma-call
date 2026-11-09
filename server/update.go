package main

// OTA updates: the admin uploads a signed APK, every device is told a new version exists,
// and the app downloads it (authenticated) and hands it to Android's package installer.

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/shogo82148/androidbinary/apk"
)

const appPackage = "az.zeng"

type appMeta struct {
	VersionCode int32  `json:"versionCode"`
	VersionName string `json:"versionName"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
	Notes       string `json:"notes"`
	Uploaded    int64  `json:"uploaded"`
}

func apkPath() string  { return filepath.Join(appDir, "call.apk") }
func metaPath() string { return filepath.Join(appDir, "meta.json") }

func loadMeta() (m appMeta, ok bool) {
	b, err := os.ReadFile(metaPath())
	if err != nil {
		return m, false
	}
	return m, json.Unmarshal(b, &m) == nil
}

// secure adds hardening headers; the admin page also gets a strict CSP.
func secure(h http.Handler, adm string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hd := w.Header()
		hd.Set("X-Content-Type-Options", "nosniff")
		hd.Set("Referrer-Policy", "no-referrer")
		hd.Set("X-Frame-Options", "DENY")
		hd.Set("X-Robots-Tag", "noindex, nofollow")
		if strings.HasPrefix(r.URL.Path, adm) {
			hd.Set("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; "+
				"style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; "+
				"img-src 'self' data:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
			hd.Set("Cache-Control", "no-store")
		}
		h.ServeHTTP(w, r)
	})
}

// bearer resolves "Authorization: Bearer <token>" to a non-banned user id.
func bearer(r *http.Request) string {
	t := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if t == "" {
		return ""
	}
	mu.Lock()
	defer mu.Unlock()
	for id, u := range db.Users {
		if eq(t, u.Token) && !u.Banned {
			return id
		}
	}
	return ""
}

func appUpdate(w http.ResponseWriter, r *http.Request) {
	if bearer(r) == "" {
		w.WriteHeader(401)
		return
	}
	m, ok := loadMeta()
	if !ok {
		writeJSON(w, map[string]any{"versionCode": 0})
		return
	}
	dl := ""
	if dlPath != "" {
		dl = "/d/" + dlPath + "/call.apk"
	}
	writeJSON(w, struct {
		appMeta
		Download string `json:"download"`
	}{m, dl})
}

func appAPK(w http.ResponseWriter, r *http.Request) {
	if bearer(r) == "" {
		w.WriteHeader(401)
		return
	}
	serveAPK(w, r)
}

func serveAPK(w http.ResponseWriter, r *http.Request) {
	f, err := os.Open(apkPath())
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="Call.apk"`)
	st, _ := f.Stat()
	http.ServeContent(w, r, "Call.apk", st.ModTime(), f)
}

// adminUpload accepts a new APK, checks it is our package with a higher version, and announces it.
func adminUpload(w http.ResponseWriter, r *http.Request) {
	if !isAdmin(r) {
		w.WriteHeader(401)
		return
	}
	if r.Method != http.MethodPost {
		w.WriteHeader(405)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 100<<20)
	file, _, err := r.FormFile("apk")
	if err != nil {
		http.Error(w, "APK faylı oxunmadı", 400)
		return
	}
	defer file.Close()

	os.MkdirAll(appDir, 0o700)
	tmp, err := os.CreateTemp(appDir, "upload-*.apk")
	if err != nil {
		http.Error(w, "disk xətası", 500)
		return
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	size, err := io.Copy(io.MultiWriter(tmp, h), file)
	tmp.Close()
	if err != nil {
		http.Error(w, "yükləmə kəsildi", 400)
		return
	}

	pkg, err := apk.OpenFile(tmp.Name())
	if err != nil {
		http.Error(w, "bu APK deyil", 400)
		return
	}
	name, code, ver := pkg.PackageName(), pkg.Manifest().VersionCode.MustInt32(), pkg.Manifest().VersionName.MustString()
	pkg.Close()
	if name != appPackage {
		http.Error(w, "başqa tətbiqin APK-sıdır: "+name, 400)
		return
	}
	if old, ok := loadMeta(); ok && code <= old.VersionCode {
		http.Error(w, "versiya köhnədir: serverdə artıq "+old.VersionName+" var. versionCode-u artırın", 400)
		return
	}
	if err := os.Rename(tmp.Name(), apkPath()); err != nil {
		http.Error(w, "disk xətası", 500)
		return
	}
	m := appMeta{VersionCode: code, VersionName: ver, SHA256: hex.EncodeToString(h.Sum(nil)), Size: size,
		Notes: clip(r.FormValue("notes"), 500), Uploaded: time.Now().Unix()}
	b, _ := json.Marshal(m)
	os.WriteFile(metaPath(), b, 0o600)

	mu.Lock()
	for _, set := range conns {
		for c := range set {
			c.send(map[string]any{"type": "update", "versionCode": m.VersionCode, "versionName": m.VersionName})
		}
	}
	mu.Unlock()
	writeJSON(w, m)
}

// ---------- custom ringtone ----------

type toneMeta struct {
	SHA256 string `json:"sha256"`
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	Type   string `json:"type"`
}

func tonePath() string     { return filepath.Join(appDir, "ringtone.bin") }
func toneMetaPath() string { return filepath.Join(appDir, "ringtone.json") }

func loadTone() (m toneMeta, ok bool) {
	b, err := os.ReadFile(toneMetaPath())
	if err != nil {
		return m, false
	}
	return m, json.Unmarshal(b, &m) == nil
}

// toneInfo is what devices get in hello/ringtone messages: nil means "use the phone's default".
func toneInfo() any {
	if m, ok := loadTone(); ok {
		return map[string]any{"sha256": m.SHA256, "name": m.Name}
	}
	return nil
}

func broadcastTone() {
	mu.Lock()
	defer mu.Unlock()
	t := toneInfo()
	for _, set := range conns {
		for c := range set {
			c.send(map[string]any{"type": "ringtone", "ringtone": t})
		}
	}
}

func appRingtone(w http.ResponseWriter, r *http.Request) {
	if bearer(r) == "" {
		w.WriteHeader(401)
		return
	}
	m, ok := loadTone()
	f, err := os.Open(tonePath())
	if !ok || err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", m.Type)
	st, _ := f.Stat()
	http.ServeContent(w, r, "ringtone", st.ModTime(), f)
}

// MP3 / AAC (m4a) only: Android 5 can't play Ogg-Opus or 24-bit WAV, the phone would ring silently.
var audioTypes = map[string]bool{"audio/mpeg": true, "audio/mp4": true, "audio/x-m4a": true, "audio/aac": true}

// adminRingtone: POST multipart "audio" sets the ringtone for every device; DELETE restores the default.
func adminRingtone(w http.ResponseWriter, r *http.Request) {
	if !isAdmin(r) {
		w.WriteHeader(401)
		return
	}
	switch r.Method {
	case http.MethodGet: // admin preview
		m, ok := loadTone()
		f, err := os.Open(tonePath())
		if !ok || err != nil {
			http.NotFound(w, r)
			return
		}
		defer f.Close()
		w.Header().Set("Content-Type", m.Type)
		st, _ := f.Stat()
		http.ServeContent(w, r, "ringtone", st.ModTime(), f)
		return
	case http.MethodDelete:
		os.Remove(tonePath())
		os.Remove(toneMetaPath())
		broadcastTone()
		w.WriteHeader(204)
		return
	case http.MethodPost:
	default:
		w.WriteHeader(405)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 6<<20)
	file, hdr, err := r.FormFile("audio")
	if err != nil {
		http.Error(w, "audio faylı oxunmadı (maks. 5 MB)", 400)
		return
	}
	defer file.Close()
	head := make([]byte, 512)
	n, _ := io.ReadFull(file, head)
	ct := http.DetectContentType(head[:n])
	if !audioTypes[ct] {
		// DetectContentType can't tell MP3s without ID3 tags or raw AAC (octet-stream) and calls m4a
		// "video/mp4": fall back to the extension for those only. A WAV or Ogg named .mp3 stays rejected.
		ext := strings.ToLower(filepath.Ext(hdr.Filename))
		byExt := map[string]string{".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".aac": "audio/aac"}
		if byExt[ext] == "" || (ct != "application/octet-stream" && ct != "video/mp4") {
			http.Error(w, "yalnız MP3 və ya M4A (köhnə telefonlar başqa formatı çala bilmir)", 400)
			return
		}
		ct = byExt[ext]
	}
	os.MkdirAll(appDir, 0o700)
	tmp, err := os.CreateTemp(appDir, "tone-*")
	if err != nil {
		http.Error(w, "disk xətası", 500)
		return
	}
	defer os.Remove(tmp.Name())
	h := sha256.New()
	size, err := io.Copy(io.MultiWriter(tmp, h), io.MultiReader(strings.NewReader(string(head[:n])), file))
	tmp.Close()
	if err != nil || size > 5<<20 {
		http.Error(w, "fayl çox böyükdür (maks. 5 MB)", 400)
		return
	}
	os.Rename(tmp.Name(), tonePath())
	m := toneMeta{SHA256: hex.EncodeToString(h.Sum(nil)), Name: clip(hdr.Filename, 80), Size: size, Type: ct}
	b, _ := json.Marshal(m)
	os.WriteFile(toneMetaPath(), b, 0o600)
	broadcastTone()
	writeJSON(w, m)
}

// appCrash stores crash reports sent by devices on their next start.
func appCrash(w http.ResponseWriter, r *http.Request) {
	uid := bearer(r)
	if uid == "" || r.Method != http.MethodPost {
		w.WriteHeader(401)
		return
	}
	b, _ := io.ReadAll(io.LimitReader(r.Body, 16<<10))
	mu.Lock()
	name := nameOf(uid)
	mu.Unlock()
	os.MkdirAll(appDir, 0o700)
	if st, err := os.Stat(filepath.Join(appDir, "crashes.log")); err == nil && st.Size() > 2<<20 {
		w.WriteHeader(204) // cap: 2 MB of reports is plenty
		return
	}
	f, err := os.OpenFile(filepath.Join(appDir, "crashes.log"), os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err == nil {
		f.WriteString("=== " + time.Now().Format(time.RFC3339) + " " + name + " (" + uid + ")\n" + string(b) + "\n")
		f.Close()
	}
	w.WriteHeader(204)
}

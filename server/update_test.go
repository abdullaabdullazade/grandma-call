package main

import (
	"bytes"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func pad(b []byte, n int) []byte { return append(b, make([]byte, n-len(b))...) }

// The ringtone goes to every phone, and Android 5 can't play WAV or Ogg. The extension is only a
// fallback for files the sniffer can't classify, it must not turn a WAV/Ogg named .mp3 into an MP3.
func TestAdminRingtoneType(t *testing.T) {
	oldDir := appDir
	appDir = t.TempDir()
	t.Cleanup(func() { appDir = oldDir })
	mu.Lock()
	sessions["test"] = time.Now().Add(time.Hour)
	mu.Unlock()
	t.Cleanup(func() { mu.Lock(); delete(sessions, "test"); mu.Unlock() })

	wav := pad([]byte("RIFF\x24\x00\x00\x00WAVEfmt "), 64)
	ogg := pad([]byte("OggS\x00\x02"), 64)
	id3 := pad([]byte("ID3\x03\x00\x00\x00\x00\x00\x00"), 64)
	mp3 := pad([]byte("\xff\xfb\x90\x00"), 64) // frame header, no ID3 tag
	m4a := pad([]byte("\x00\x00\x00\x20ftypM4A \x00\x00\x00\x00M4A mp42isom"), 64)

	for _, c := range []struct {
		name, file string
		body       []byte
		status     int
		typ        string
	}{
		{"wav named mp3", "ring.mp3", wav, 400, ""},
		{"ogg named mp3", "ring.mp3", ogg, 400, ""},
		{"wav named m4a", "ring.m4a", wav, 400, ""},
		{"unknown bytes named wav", "ring.wav", mp3, 400, ""},
		{"mp3 with ID3", "ring.mp3", id3, 200, "audio/mpeg"},
		{"mp3 without ID3", "ring.mp3", mp3, 200, "audio/mpeg"},
		{"m4a", "ring.m4a", m4a, 200, "audio/mp4"},
	} {
		t.Run(c.name, func(t *testing.T) {
			var buf bytes.Buffer
			mw := multipart.NewWriter(&buf)
			fw, _ := mw.CreateFormFile("audio", c.file)
			fw.Write(c.body)
			mw.Close()
			req := httptest.NewRequest(http.MethodPost, "/api/ringtone", &buf)
			req.Header.Set("Content-Type", mw.FormDataContentType())
			req.AddCookie(&http.Cookie{Name: "zeng_admin", Value: "test"})
			rec := httptest.NewRecorder()
			adminRingtone(rec, req)
			if rec.Code != c.status {
				t.Fatalf("status %d, want %d (%s)", rec.Code, c.status, rec.Body.String())
			}
			if c.typ == "" {
				return
			}
			if m, ok := loadTone(); !ok || m.Type != c.typ {
				t.Fatalf("stored type %q (ok=%v), want %q", m.Type, ok, c.typ)
			}
		})
	}
}

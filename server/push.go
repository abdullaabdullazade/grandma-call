package main

// FCM wake-up: when a callee's phone has no live connection, Google wakes the app (like WhatsApp)
// so it reconnects and the call rings within seconds. Uses FCM HTTP v1 with a service-account key;
// no SDK, just a signed JWT exchanged for an OAuth token.

import (
	"bytes"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

var fcm struct {
	sync.Mutex
	project, email, tokenURI string
	key                      *rsa.PrivateKey
	access                   string
	expires                  time.Time
}

// loadFCM reads the service-account JSON named by FCM_KEY; push stays off without it.
func loadFCM() {
	path := os.Getenv("FCM_KEY")
	if path == "" {
		return
	}
	b, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		log.Print("fcm: no key file, push wake-ups disabled (optional)")
		return
	}
	if err != nil {
		log.Print("fcm: ", err)
		return
	}
	var sa struct {
		ProjectID   string `json:"project_id"`
		ClientEmail string `json:"client_email"`
		PrivateKey  string `json:"private_key"`
		TokenURI    string `json:"token_uri"`
	}
	if json.Unmarshal(b, &sa) != nil {
		log.Print("fcm: bad key file")
		return
	}
	blk, _ := pem.Decode([]byte(sa.PrivateKey))
	if blk == nil {
		log.Print("fcm: no private key")
		return
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	rk, ok := k.(*rsa.PrivateKey)
	if err != nil || !ok {
		log.Print("fcm: unsupported key")
		return
	}
	fcm.project, fcm.email, fcm.tokenURI, fcm.key = sa.ProjectID, sa.ClientEmail, sa.TokenURI, rk
	log.Printf("fcm: push enabled for project %s", sa.ProjectID)
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// accessToken returns a cached OAuth token, refreshing it ~5 min before expiry.
func accessToken() (string, error) {
	fcm.Lock()
	defer fcm.Unlock()
	if fcm.access != "" && time.Now().Before(fcm.expires) {
		return fcm.access, nil
	}
	now := time.Now().Unix()
	hdr, _ := json.Marshal(map[string]string{"alg": "RS256", "typ": "JWT"})
	claims, _ := json.Marshal(map[string]any{"iss": fcm.email, "scope": "https://www.googleapis.com/auth/firebase.messaging",
		"aud": fcm.tokenURI, "iat": now, "exp": now + 3600})
	unsigned := b64(hdr) + "." + b64(claims)
	sum := sha256.Sum256([]byte(unsigned))
	sig, err := rsa.SignPKCS1v15(nil, fcm.key, crypto.SHA256, sum[:])
	if err != nil {
		return "", err
	}
	resp, err := (&http.Client{Timeout: 10 * time.Second}).PostForm(fcm.tokenURI, url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"}, "assertion": {unsigned + "." + b64(sig)}})
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var t struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if json.NewDecoder(resp.Body).Decode(&t) != nil || t.AccessToken == "" {
		return "", fmt.Errorf("oauth status %d", resp.StatusCode)
	}
	fcm.access, fcm.expires = t.AccessToken, time.Now().Add(time.Duration(t.ExpiresIn-300)*time.Second)
	return fcm.access, nil
}

// pushWake asks Google to wake the user's phone. Runs async: never call it waiting on mu. caller holds mu.
func pushWake(uid, callId, from string) {
	u := db.Users[uid]
	if fcm.key == nil || u == nil || u.Push == "" {
		return
	}
	token, name, video := u.Push, nameOf(from), "0"
	if r := rooms[callId]; r != nil && r.video {
		video = "1"
	}
	go func() {
		at, err := accessToken()
		if err != nil {
			log.Print("fcm: token: ", err)
			return
		}
		body, _ := json.Marshal(map[string]any{"message": map[string]any{
			"token": token,
			// caller details let the phone ring immediately, before its socket reconnects
			"data": map[string]string{"type": "wake", "callId": callId, "from": from, "name": name, "video": video},
			// high priority + short TTL: wakes a sleeping phone now, never delivers a stale call later
			"android": map[string]any{"priority": "HIGH", "ttl": "40s"},
		}})
		req, _ := http.NewRequest("POST", "https://fcm.googleapis.com/v1/projects/"+fcm.project+"/messages:send", bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+at)
		req.Header.Set("Content-Type", "application/json")
		resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
		if err != nil {
			log.Print("fcm: send: ", err)
			return
		}
		defer resp.Body.Close()
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 2000))
		if resp.StatusCode == 200 {
			log.Printf("call %s: woke %s via FCM", short(callId), nameOf(uid))
			return
		}
		log.Printf("fcm: %s -> %d %s", nameOf(uid), resp.StatusCode, strings.TrimSpace(string(msg)))
		if resp.StatusCode == 404 || strings.Contains(string(msg), "UNREGISTERED") { // app reinstalled: forget the token
			mu.Lock()
			if x := db.Users[uid]; x != nil && x.Push == token {
				x.Push = ""
				save()
			}
			mu.Unlock()
		}
	}()
}

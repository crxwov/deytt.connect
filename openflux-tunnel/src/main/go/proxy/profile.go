package proxy

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"regexp"
	"strings"
	"time"
)

const MaxProfileBytes = 16 << 10
const MaxRooms = 4

type TransportSpec struct {
	Type     string `json:"type"`
	Name     string `json:"name"`
	URL      string `json:"url"`
	Priority int    `json:"priority"`
}

type Profile struct {
	Version    int             `json:"version,omitempty"`
	ID         string          `json:"id"`
	Country    string          `json:"country"`
	Label      string          `json:"label,omitempty"`
	ExpiresAt  int64           `json:"expires_at"`
	Secret     string          `json:"secret"`
	Context    string          `json:"context"`
	Transports []TransportSpec `json:"transports"`
}

var roomID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func ParseProfile(data []byte, now time.Time, allowProvision bool) (Profile, error) {
	var p Profile
	if len(data) == 0 || len(data) > MaxProfileBytes {
		return p, errors.New("invalid OpenFlux profile size")
	}
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if d.Decode(&p) != nil {
		return p, errors.New("invalid OpenFlux profile")
	}
	if d.Decode(new(any)) != io.EOF {
		return p, errors.New("invalid trailing profile data")
	}
	if p.Version != 0 && p.Version != 1 {
		return p, errors.New("unsupported OpenFlux version")
	}
	if p.ID != "openflux:DE" || p.Country != "DE" {
		return p, errors.New("invalid OpenFlux route")
	}
	if len(p.Label) > 128 {
		return p, errors.New("invalid OpenFlux label")
	}
	// The manager renews/revokes the live worker; expiry here is an admission
	// check, not an immutable runtime timer that would break lease renewal.
	if p.ExpiresAt <= now.Unix() || p.ExpiresAt > now.Add(24*time.Hour).Unix() {
		return p, errors.New("OpenFlux profile expired or lifetime invalid")
	}
	key, err := base64.RawURLEncoding.DecodeString(p.Secret)
	if err != nil || len(key) != 32 || base64.RawURLEncoding.EncodeToString(key) != p.Secret {
		return p, errors.New("invalid OpenFlux secret")
	}
	if len(p.Context) < 16 || len(p.Context) > 256 || strings.TrimSpace(p.Context) != p.Context {
		return p, errors.New("invalid OpenFlux context")
	}
	if len(p.Transports) != 1 {
		return p, errors.New("exactly one Cups carrier required")
	}
	s := p.Transports[0]
	if s.Type != "cupsonline" || s.Name != "cupsonline" || s.Priority != 100 {
		return p, errors.New("unsupported OpenFlux carrier")
	}
	if s.URL == "" && allowProvision {
		return p, nil
	}
	if !ValidRooms(s.URL) {
		return p, errors.New("invalid Cups rooms")
	}
	return p, nil
}

func ValidRooms(packed string) bool {
	if len(packed) == 0 || len(packed) > 512 {
		return false
	}
	data, err := base64.RawURLEncoding.DecodeString(packed)
	if err != nil || base64.RawURLEncoding.EncodeToString(data) != packed {
		return false
	}
	var rooms []string
	if json.Unmarshal(data, &rooms) != nil || len(rooms) == 0 || len(rooms) > MaxRooms {
		return false
	}
	seen := make(map[string]bool)
	for _, room := range rooms {
		if !roomID.MatchString(room) || seen[room] {
			return false
		}
		seen[room] = true
	}
	return true
}

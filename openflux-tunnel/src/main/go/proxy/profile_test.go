package proxy

import (
	"encoding/base64"
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func testProfile() Profile {
	return Profile{Version: 1, ID: "openflux:DE", Country: "DE", ExpiresAt: time.Now().Add(time.Hour).Unix(),
		Secret: base64.RawURLEncoding.EncodeToString(make([]byte, 32)), Context: "deytt-test-context-0123456789",
		Transports: []TransportSpec{{Type: "cupsonline", Name: "cupsonline", Priority: 100, URL: base64.RawURLEncoding.EncodeToString([]byte(`["01234567-89ab-cdef-0123-456789abcdef"]`))}}}
}
func encodeProfile(p Profile) []byte { b, _ := json.Marshal(p); return b }

func TestProfileAdmission(t *testing.T) {
	now := time.Now()
	for _, tc := range []struct {
		name      string
		edit      func(*Profile)
		valid     bool
		provision bool
	}{
		{"valid", func(p *Profile) {}, true, false},
		{"expired", func(p *Profile) { p.ExpiresAt = now.Unix() }, false, false},
		{"too long", func(p *Profile) { p.ExpiresAt = now.Add(25 * time.Hour).Unix() }, false, false},
		{"wrong route", func(p *Profile) { p.ID = "openflux:RU" }, false, false},
		{"short key", func(p *Profile) { p.Secret = "insecure" }, false, false},
		{"bad context", func(p *Profile) { p.Context = "" }, false, false},
		{"direct forbidden", func(p *Profile) { p.Transports[0].Type = "direct" }, false, false},
		{"extra carrier", func(p *Profile) { p.Transports = append(p.Transports, p.Transports[0]) }, false, false},
		{"blank client", func(p *Profile) { p.Transports[0].URL = "" }, false, false},
		{"exit provision", func(p *Profile) { p.Transports[0].URL = "" }, true, true},
		{"arbitrary URL", func(p *Profile) { p.Transports[0].URL = "https://example.com" }, false, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			p := testProfile()
			tc.edit(&p)
			_, err := ParseProfile(encodeProfile(p), now, tc.provision)
			if (err == nil) != tc.valid {
				t.Fatalf("valid=%v err=%v", tc.valid, err)
			}
		})
	}
	valid := encodeProfile(testProfile())
	for _, data := range [][]byte{append(valid, []byte(` {}`)...), []byte(strings.Repeat("x", MaxProfileBytes+1)), []byte(`{"secret":"private","unknown":true}`)} {
		if _, err := ParseProfile(data, now, false); err == nil {
			t.Fatal("accepted invalid JSON envelope")
		}
	}
}

func TestRoomsBounded(t *testing.T) {
	pack := func(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }
	for _, raw := range []string{`[]`, `["../other"]`, `["01234567-89ab-cdef-0123-456789abcdef","01234567-89ab-cdef-0123-456789abcdef"]`, `["01234567-89AB-cdef-0123-456789abcdef"]`, `{}`} {
		if ValidRooms(pack(raw)) {
			t.Fatal("accepted invalid rooms")
		}
	}
}

func TestSocksDestinationsCannotLeakDNS(t *testing.T) {
	for _, address := range []string{"example.com:443", "[2001:db8::1]:443", "bad"} {
		if requireIPv4(address) == nil {
			t.Fatal("accepted hostname or IPv6")
		}
	}
	if err := requireIPv4("1.1.1.1:443"); err != nil {
		t.Fatal(err)
	}
}

func TestProtectionFailurePrecedesCarrier(t *testing.T) {
	calls := 0
	_, err := Start(string(encodeProfile(testProfile())), "0123456789abcdef", "0123456789abcdef", []string{"1.1.1.1"}, func(fd int) bool { calls++; return false })
	if err == nil || !strings.Contains(err.Error(), "protection rejected") || calls != 1 {
		t.Fatalf("calls=%d err=%v", calls, err)
	}
	if active != nil {
		t.Fatal("failed start retained session")
	}
}

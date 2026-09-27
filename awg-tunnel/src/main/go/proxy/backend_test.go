package proxy

import (
	"crypto/rand"
	"encoding/hex"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
	"golang.org/x/crypto/curve25519"
)

func testKey(t *testing.T) (string, string) {
	t.Helper()
	key := make([]byte, 32)
	if _, err := rand.Read(key); err != nil {
		t.Fatal(err)
	}
	public, err := curve25519.X25519(key, curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(key), hex.EncodeToString(public)
}

// These tests exchange actual encrypted TCP and UDP traffic between two AWG
// peers, with both old single-header settings and the extended v3 settings.
func TestEncryptedAWGNetstackRoundTrip(t *testing.T) {
	profiles := map[string]string{
		"compatible_single_headers":    "jc=1\njmin=20\njmax=50\ns1=16\ns2=24\nh1=100\nh2=200\nh3=300\nh4=400\n",
		"extended_headers_and_padding": "jc=1\njmin=20\njmax=50\ns1=16\ns2=24\ns3=8\ns4=12\nh1=100-110\nh2=200-210\nh3=300-310\nh4=400-410\n",
	}
	for name, obfuscation := range profiles {
		t.Run(name, func(t *testing.T) {
			clientPrivate, clientPublic := testKey(t)
			serverPrivate, serverPublic := testKey(t)
			tun, network, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.0.0.2")}, nil, 1280)
			if err != nil {
				t.Fatal(err)
			}
			server := device.NewDevice(tun, conn.NewStdNetBind(), &device.Logger{Verbosef: func(string, ...any) {}, Errorf: func(string, ...any) {}})
			t.Cleanup(server.Close)
			serverConfig := "private_key=" + serverPrivate + "\nlisten_port=0\n" + obfuscation + "public_key=" + clientPublic + "\nallowed_ip=10.0.0.1/32\n"
			if err := server.IpcSet(serverConfig); err != nil {
				t.Fatal("server settings rejected")
			}
			if err := server.Up(); err != nil {
				t.Fatal(err)
			}
			config, err := server.IpcGet()
			if err != nil {
				t.Fatal("cannot inspect listen port")
			}
			port := ""
			for _, line := range strings.Split(config, "\n") {
				if strings.HasPrefix(line, "listen_port=") {
					port = strings.TrimPrefix(line, "listen_port=")
				}
			}
			if port == "" || port == "0" {
				t.Fatal("server has no listen port")
			}
			clientConfig := "private_key=" + clientPrivate + "\n" + obfuscation + "public_key=" + serverPublic + "\nallowed_ip=0.0.0.0/0\nendpoint=127.0.0.1:" + port + "\n"
			backend, err := Start(clientConfig, "10.0.0.1", "", 1280, testUser, testPassword, func(int) bool { return true })
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(backend.Close)
			listener, err := network.ListenTCPAddrPort(netip.MustParseAddrPort("10.0.0.2:8080"))
			if err != nil {
				t.Fatal(err)
			}
			defer listener.Close()
			go func() {
				c, err := listener.Accept()
				if err != nil {
					return
				}
				defer c.Close()
				io.Copy(c, c)
			}()
			client, status := authenticateTest(t, backend.server, testPassword)
			if status != 0 {
				t.Fatal("auth")
			}
			client.Write([]byte{5, 1, 0, 1, 10, 0, 0, 2, 31, 144})
			response := make([]byte, 10)
			if _, err := io.ReadFull(client, response); err != nil || response[1] != 0 {
				t.Fatalf("encrypted TCP connect failed: %v", err)
			}
			client.Write([]byte("encrypted"))
			data := make([]byte, 9)
			if _, err := io.ReadFull(client, data); err != nil || string(data) != "encrypted" {
				t.Fatalf("encrypted TCP failed: %v", err)
			}
			client.Close()

			udpEcho, err := network.ListenUDP(&net.UDPAddr{IP: net.IPv4(10, 0, 0, 2), Port: 8081})
			if err != nil {
				t.Fatal(err)
			}
			defer udpEcho.Close()
			go func() {
				buffer := make([]byte, 1024)
				n, addr, err := udpEcho.ReadFrom(buffer)
				if err == nil {
					udpEcho.WriteTo(buffer[:n], addr)
				}
			}()
			control, status := authenticateTest(t, backend.server, testPassword)
			if status != 0 {
				t.Fatal("auth")
			}
			response = request(t, control, 3, 0)
			proxyPort := int(response[8])<<8 | int(response[9])
			udpClient, err := net.Dial("udp", net.JoinHostPort("127.0.0.1", strconv.Itoa(proxyPort)))
			if err != nil {
				t.Fatal(err)
			}
			defer udpClient.Close()
			udpClient.SetDeadline(time.Now().Add(3 * time.Second))
			udpClient.Write(append([]byte{0, 0, 0, 1, 10, 0, 0, 2, 31, 145}, []byte("encrypted")...))
			buffer := make([]byte, 1024)
			n, err := udpClient.Read(buffer)
			if err != nil || n < 10 || string(buffer[10:n]) != "encrypted" {
				t.Fatalf("encrypted UDP failed: %v", err)
			}
		})
	}
}

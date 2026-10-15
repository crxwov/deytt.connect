package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"space.deytt/openflux/proxy"
	"syscall"
	"time"
)

func readConfig(path string) ([]byte, error) {
	var source io.Reader = os.Stdin
	if path != "-" {
		f, err := os.Open(path)
		if err != nil {
			return nil, errors.New("config unavailable")
		}
		defer f.Close()
		info, err := f.Stat()
		if err != nil || !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 {
			return nil, errors.New("config requires a private regular file")
		}
		source = f
	}
	data, err := io.ReadAll(io.LimitReader(source, proxy.MaxProfileBytes+1))
	if err != nil || len(data) > proxy.MaxProfileBytes {
		return nil, errors.New("config exceeds limit")
	}
	return data, nil
}

func run() error {
	path := flag.String("config", "", "private JSON file, or - for stdin")
	flag.Parse()
	if *path == "" || flag.NArg() != 0 {
		return errors.New("usage: openflux-exit --config private.json")
	}
	data, err := readConfig(*path)
	if err != nil {
		return err
	}
	p, err := proxy.ParseProfile(data, time.Now(), true)
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	exit, rooms, err := proxy.StartExit(ctx, p)
	if err != nil {
		return err
	}
	defer exit.Close()
	if err := json.NewEncoder(os.Stdout).Encode(struct {
		Ready bool   `json:"ready"`
		Rooms string `json:"rooms_url"`
	}{true, rooms}); err != nil {
		return errors.New("ready event unavailable")
	}
	<-ctx.Done()
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err.Error())
		os.Exit(1)
	}
}

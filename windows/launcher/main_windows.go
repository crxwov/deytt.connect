//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"
)

func main() {
	launcher, err := os.Executable()
	if err != nil {
		showMissingPackage()
		return
	}
	appDirectory := filepath.Join(filepath.Dir(launcher), "app")
	app := filepath.Join(appDirectory, "DeyttConnect.Windows.exe")
	if _, err := os.Stat(app); err != nil {
		showMissingPackage()
		return
	}

	command := exec.Command(app, os.Args[1:]...)
	command.Dir = appDirectory
	if command.Start() != nil {
		showMissingPackage()
	}
}

func showMissingPackage() {
	message, _ := syscall.UTF16PtrFromString("Не удалось открыть DEYTT Connect. Распакуйте весь архив и запустите deyttconnect.exe снова.")
	title, _ := syscall.UTF16PtrFromString("DEYTT Connect")
	syscall.NewLazyDLL("user32.dll").NewProc("MessageBoxW").Call(
		0, uintptr(unsafe.Pointer(message)), uintptr(unsafe.Pointer(title)), 0x10)
}

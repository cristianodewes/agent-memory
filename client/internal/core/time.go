package core

import "time"

// nowUnixMilli is the single clock read used when minting UUIDv7 ids. Isolated here so the rest of
// the package stays clock-free and so it is obvious this is the only wall-clock dependency.
func nowUnixMilli() int64 {
	return time.Now().UnixMilli()
}

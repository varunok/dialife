// Package auth issues device tokens and checks them on every API request.
package auth

import (
	"crypto/rand"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"math/big"
	"strings"
)

type Role string

const (
	RoleUploader  Role = "uploader"  // xDrip+ on the phone that reads the sensor
	RoleCaregiver Role = "caregiver" // parents' apps
	RoleReader    Role = "reader"    // panels, dashboards
)

func ParseRole(s string) (Role, error) {
	switch r := Role(s); r {
	case RoleUploader, RoleCaregiver, RoleReader:
		return r, nil
	}
	return "", fmt.Errorf("unknown role %q (want uploader, caregiver or reader)", s)
}

const (
	tokenPrefix = "dl_"
	tokenBytes  = 32
	tokenDigits = 43 // 62^43 > 2^256, so every 32-byte value fits
)

// WebTokenPrefix marks a browser session token: read-only, given by a parent's approval.
const WebTokenPrefix = "dlw_"

// NewWebToken is a browser session token: "dlw_" + 32 random bytes.
func NewWebToken() (string, error) {
	t, err := NewToken()
	return WebTokenPrefix + strings.TrimPrefix(t, tokenPrefix), err
}

// NewToken returns "dl_" + 32 random bytes in fixed-width base62.
func NewToken() (string, error) {
	b := make([]byte, tokenBytes)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	s := new(big.Int).SetBytes(b).Text(62)
	return tokenPrefix + strings.Repeat("0", tokenDigits-len(s)) + s, nil
}

// Hash is what the database stores. It is also exactly what xDrip+ sends
// in the api-secret header: sha1 of the secret as lowercase hex.
func Hash(token string) string {
	sum := sha1.Sum([]byte(token))
	return hex.EncodeToString(sum[:])
}

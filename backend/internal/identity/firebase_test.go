package identity

import (
	"testing"

	firebaseauth "firebase.google.com/go/v4/auth"
)

func TestUserFromTokenExtractsOnlyStableProfileClaims(t *testing.T) {
	user, err := userFromToken(&firebaseauth.Token{
		UID: "firebase-user-1",
		Claims: map[string]any{
			"email":          "rider@example.com",
			"name":           "Trail Rider",
			"picture":        "https://example.com/avatar.jpg",
			"email_verified": true,
			"admin":          true,
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if user.UID != "firebase-user-1" || user.Email != "rider@example.com" ||
		user.DisplayName != "Trail Rider" || !user.EmailVerified {
		t.Fatalf("unexpected user: %+v", user)
	}
}

func TestUserFromTokenRejectsMissingUID(t *testing.T) {
	if _, err := userFromToken(&firebaseauth.Token{}); err == nil {
		t.Fatal("expected missing uid error")
	}
}

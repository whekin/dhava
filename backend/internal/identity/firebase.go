// Package identity verifies external identity-provider tokens for the API.
package identity

import (
	"context"
	"errors"
	"fmt"

	firebase "firebase.google.com/go/v4"
	firebaseauth "firebase.google.com/go/v4/auth"
)

// User is the verified identity carried by a Firebase ID token.
type User struct {
	UID           string
	Email         string
	DisplayName   string
	AvatarURL     string
	EmailVerified bool
}

// Verifier is the API seam for verifying a bearer token.
type Verifier interface {
	Verify(context.Context, string) (User, error)
}

// FirebaseVerifier verifies tokens with the official Firebase Admin SDK.
type FirebaseVerifier struct {
	client *firebaseauth.Client
}

// NewFirebaseVerifier initializes Firebase with Application Default Credentials.
// Outside Google infrastructure, GOOGLE_APPLICATION_CREDENTIALS must point to the
// mounted service-account JSON file.
func NewFirebaseVerifier(ctx context.Context, projectID string) (*FirebaseVerifier, error) {
	if projectID == "" {
		return nil, errors.New("firebase project id is required")
	}
	app, err := firebase.NewApp(ctx, &firebase.Config{ProjectID: projectID})
	if err != nil {
		return nil, fmt.Errorf("initialize firebase app: %w", err)
	}
	client, err := app.Auth(ctx)
	if err != nil {
		return nil, fmt.Errorf("initialize firebase auth: %w", err)
	}
	return &FirebaseVerifier{client: client}, nil
}

// Verify validates signature, issuer, audience and expiry, then extracts only the
// profile claims Nakvali stores locally. Raw tokens and arbitrary claims are never
// persisted or logged.
func (v *FirebaseVerifier) Verify(ctx context.Context, rawToken string) (User, error) {
	token, err := v.client.VerifyIDToken(ctx, rawToken)
	if err != nil {
		return User{}, fmt.Errorf("verify firebase id token: %w", err)
	}
	return userFromToken(token)
}

func userFromToken(token *firebaseauth.Token) (User, error) {
	if token == nil || token.UID == "" {
		return User{}, errors.New("firebase token has no uid")
	}
	return User{
		UID:           token.UID,
		Email:         stringClaim(token.Claims, "email"),
		DisplayName:   stringClaim(token.Claims, "name"),
		AvatarURL:     stringClaim(token.Claims, "picture"),
		EmailVerified: boolClaim(token.Claims, "email_verified"),
	}, nil
}

func stringClaim(claims map[string]any, key string) string {
	value, _ := claims[key].(string)
	return value
}

func boolClaim(claims map[string]any, key string) bool {
	value, _ := claims[key].(bool)
	return value
}

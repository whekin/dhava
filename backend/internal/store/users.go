package store

import (
	"context"
	"fmt"
)

// FirebaseIdentity is the verified, minimal profile received from Firebase Auth.
type FirebaseIdentity struct {
	UID           string
	Email         string
	DisplayName   string
	AvatarURL     string
	EmailVerified bool
}

// User is Nakvali's local account record. Firebase UID is the external identity;
// the UUID remains the stable internal key for product data.
type User struct {
	ID            string
	Email         string
	DisplayName   string
	AvatarURL     string
	EmailVerified bool
}

// UpsertFirebaseUser creates or refreshes the local profile for a verified UID.
func (s *Store) UpsertFirebaseUser(ctx context.Context, identity FirebaseIdentity) (User, error) {
	var user User
	err := s.pool.QueryRow(ctx,
		`INSERT INTO users (firebase_uid, email, display_name, avatar_url, email_verified)
		 VALUES ($1, NULLIF($2, ''), NULLIF($3, ''), NULLIF($4, ''), $5)
		 ON CONFLICT (firebase_uid) DO UPDATE SET
		     email = EXCLUDED.email,
		     display_name = EXCLUDED.display_name,
		     avatar_url = EXCLUDED.avatar_url,
		     email_verified = EXCLUDED.email_verified,
		     updated_at = now()
		 RETURNING id, COALESCE(email, ''), COALESCE(display_name, ''),
		           COALESCE(avatar_url, ''), email_verified`,
		identity.UID,
		identity.Email,
		identity.DisplayName,
		identity.AvatarURL,
		identity.EmailVerified,
	).Scan(
		&user.ID,
		&user.Email,
		&user.DisplayName,
		&user.AvatarURL,
		&user.EmailVerified,
	)
	if err != nil {
		return User{}, fmt.Errorf("upsert firebase user: %w", err)
	}
	return user, nil
}

package api

import (
	"context"
	"net/http"
	"strings"

	"github.com/whekin/nakvali/backend/internal/identity"
	"github.com/whekin/nakvali/backend/internal/store"
)

type identityContextKey struct{}

func (s *Server) requireFirebaseIdentity(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.identity == nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "auth_unavailable"})
			return
		}

		rawToken, ok := bearerToken(r.Header.Get("Authorization"))
		if !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "firebase_token_required"})
			return
		}
		verified, err := s.identity.Verify(r.Context(), rawToken)
		if err != nil {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "invalid_firebase_token"})
			return
		}

		ctx := context.WithValue(r.Context(), identityContextKey{}, verified)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func bearerToken(header string) (string, bool) {
	scheme, token, found := strings.Cut(strings.TrimSpace(header), " ")
	return token, found && strings.EqualFold(scheme, "Bearer") && token != "" && !strings.Contains(token, " ")
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	verified, ok := r.Context().Value(identityContextKey{}).(identity.User)
	if !ok {
		writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "firebase_token_required"})
		return
	}
	if s.db == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}

	user, err := s.db.UpsertFirebaseUser(r.Context(), store.FirebaseIdentity{
		UID:           verified.UID,
		Email:         verified.Email,
		DisplayName:   verified.DisplayName,
		AvatarURL:     verified.AvatarURL,
		EmailVerified: verified.EmailVerified,
	})
	if err != nil {
		s.logger.Error("firebase user upsert failed", "error", err)
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "db_unavailable"})
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"id":             user.ID,
		"email":          user.Email,
		"display_name":   user.DisplayName,
		"avatar_url":     user.AvatarURL,
		"email_verified": user.EmailVerified,
	})
}

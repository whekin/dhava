package config

import "testing"

func TestLoadPrivateAlphaSettings(t *testing.T) {
	t.Setenv("API_ACCESS_KEY", "  alpha-secret  ")
	t.Setenv("RAW_UPLOADS_ENABLED", "true")
	t.Setenv("FIREBASE_PROJECT_ID", "  nakvali-app  ")

	config := Load()

	if config.APIAccessKey != "alpha-secret" {
		t.Fatalf("APIAccessKey = %q, want trimmed value", config.APIAccessKey)
	}
	if !config.RawUploadsEnabled {
		t.Fatal("RawUploadsEnabled = false, want true")
	}
	if config.FirebaseProjectID != "nakvali-app" {
		t.Fatalf("FirebaseProjectID = %q, want trimmed value", config.FirebaseProjectID)
	}
}

func TestLoadKeepsRawUploadsDisabledByDefault(t *testing.T) {
	t.Setenv("RAW_UPLOADS_ENABLED", "")

	if Load().RawUploadsEnabled {
		t.Fatal("RawUploadsEnabled = true, want false")
	}
}

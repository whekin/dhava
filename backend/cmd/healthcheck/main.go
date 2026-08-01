// Command healthcheck verifies that the API is ready to receive private-alpha traffic.
package main

import (
	"net/http"
	"os"
	"time"
)

func main() {
	url := os.Getenv("HEALTHCHECK_URL")
	if url == "" {
		url = "http://127.0.0.1:8080/readyz"
	}

	client := &http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(url)
	if err != nil {
		os.Exit(1)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		os.Exit(1)
	}
}

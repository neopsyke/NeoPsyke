package main

import (
	"os"

	"github.com/atomitl/neopsyke/freud/cli/cmd"
)

func main() {
	if err := cmd.Execute(); err != nil {
		os.Exit(1)
	}
}

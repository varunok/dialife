.PHONY: test test-web lint build build-linux run deploy

# the same version on every machine, no install needed
GOLANGCI = go run github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.15.0

test: lint test-web
	go test ./...

# the web viewer's own tests; node is not needed anywhere else, so without it they are skipped
test-web:
	@if command -v node >/dev/null; then node --test internal/web/test/; else echo "node not found: web viewer tests skipped"; fi

# go vet, staticcheck, errcheck, unused, gofmt, goimports (.golangci.yml)
lint:
	$(GOLANGCI) run ./...
	$(GOLANGCI) fmt --diff ./...

build:
	go build -o bin/dialife ./cmd/dialife

build-linux:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o bin/dialife-linux-amd64 ./cmd/dialife

run: build
	DIALIFE_DB=data/dialife.db bin/dialife serve

deploy:
	deploy/deploy.sh

.PHONY: test lint build build-linux run deploy

# the same version on every machine, no install needed
GOLANGCI = go run github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.15.0

test: lint
	go test ./...

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

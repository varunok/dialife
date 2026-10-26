.PHONY: test build build-linux run deploy

test:
	go vet ./...
	go test ./...

build:
	go build -o bin/dialife ./cmd/dialife

build-linux:
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o bin/dialife-linux-amd64 ./cmd/dialife

run: build
	DIALIFE_DB=data/dialife.db bin/dialife serve

deploy:
	deploy/deploy.sh

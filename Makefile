.PHONY: up down logs restart build test

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f bot

restart:
	docker compose restart bot

build:
	cd app && ./gradlew build

test:
	cd app && ./gradlew test

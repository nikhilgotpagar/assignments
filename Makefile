.PHONY: up down logs burst test build

up:
	docker compose up --build -d

down:
	docker compose down

logs:
	docker compose logs -f app

build:
	./mvnw -B -DskipTests package

burst:
	./burst.sh http://localhost:8080

test:
	./mvnw -B test

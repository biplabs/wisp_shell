run-cloud:
	cargo run -p wispshell-cloud

run-agent:
	cargo run -p wispshell-agent -- run --local-tcp 127.0.0.1:7777

pair-agent:
	cargo run -p wispshell-agent -- pair

test:
	cargo test --workspace

android:
	cd components/android && ./gradlew installDebug

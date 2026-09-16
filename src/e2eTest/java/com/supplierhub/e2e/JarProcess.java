package com.supplierhub.e2e;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class JarProcess implements AutoCloseable {

	private static final Pattern STARTED_PORT = Pattern.compile(
		"Tomcat started on port (\\d+)"
	);

	private final Process process;
	private final Path logFile;
	private final Thread shutdownHook;

	JarProcess(Path jar, Path workingDirectory, Path logFile, String... arguments)
		throws IOException {
		if (!Files.isRegularFile(jar)) {
			throw new IllegalArgumentException("실행할 JAR가 없습니다: " + jar);
		}
		this.logFile = logFile;
		List<String> command = new ArrayList<>(List.of(
			System.getProperty("e2e.java.executable"),
			"-jar", jar.toString(),
			"--server.address=127.0.0.1",
			"--server.port=0",
			"--spring.main.banner-mode=off",
			"--spring.output.ansi.enabled=never",
			"--spring.lifecycle.timeout-per-shutdown-phase=2s"
		));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command)
			.directory(workingDirectory.toFile())
			.redirectErrorStream(true)
			.redirectOutput(logFile.toFile());
		// 사용자 환경변수와 .env가 테스트 설정에 섞이지 않게 한다.
		builder.environment().clear();
		builder.environment().put("LANG", "C.UTF-8");
		this.process = builder.start();
		// 테스트 JVM이 종료될 때도 자식 서버를 정리한다.
		this.shutdownHook = new Thread(() -> process.destroyForcibly());
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	URI awaitAddress() throws IOException, InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
		while (System.nanoTime() < deadline) {
			ensureAlive();
			var matcher = STARTED_PORT.matcher(Files.readString(logFile));
			if (matcher.find()) {
				return URI.create("http://127.0.0.1:" + matcher.group(1));
			}
			TimeUnit.MILLISECONDS.sleep(100);
		}
		throw new IllegalStateException("서버 기동 제한시간 초과. 로그: " + logFile);
	}

	void ensureAlive() {
		if (!process.isAlive()) {
			throw new IllegalStateException(
				"JAR 프로세스 종료(exit=" + process.exitValue() + "). 로그: " + logFile
			);
		}
	}

	@Override
	public void close() {
		process.destroy();
		try {
			if (!process.waitFor(8, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("프로세스 종료 실패. 로그: " + logFile);
				}
			}
		} catch (InterruptedException exception) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IllegalStateException("프로세스 종료 대기 중 인터럽트", exception);
		} finally {
			if (!process.isAlive()) {
				try {
					Runtime.getRuntime().removeShutdownHook(shutdownHook);
				} catch (IllegalStateException ignored) {
					// JVM 종료가 시작됐다면 등록한 정리를 그대로 둔다.
				}
			}
		}
	}

}

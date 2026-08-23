package uk.co.bithatch.opensim.spawner.opensim;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

public class OpensimRESTConsole implements AutoCloseable {

	public enum PromptDetectionMode {
		STRICT,
		RELAXED
	}

	private final String baseUrl;
	private final HttpClient httpClient;
	private final Optional<String> username;
	private final Optional<char[]> password;
	private final boolean debugEnabled;
	private final PromptDetectionMode promptDetectionMode;
	private final BlockingQueue<ConsoleLine> receivedLines = new LinkedBlockingQueue<>();
	private final AtomicInteger lastSeenLineNumber = new AtomicInteger(-1);
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final Object commandLock = new Object();
	private static final long RECOVERY_RETRY_DELAY_MS = 1500L;

	private volatile String sessionId;
	private volatile String prompt = "";
	private volatile List<String> discoveredModules = List.of();
	private Thread receiverThread;

	public OpensimRESTConsole(String url, Optional<String> username, Optional<char[]> password) {
		this(url, username, password, Boolean.parseBoolean(System.getProperty("opensim.debug", "false")),
				PromptDetectionMode.RELAXED);
	}

	public OpensimRESTConsole(String url, Optional<String> username, Optional<char[]> password, boolean debugEnabled,
			PromptDetectionMode promptDetectionMode) {
		this.baseUrl = normalizeUrl(url);
		this.username = username;
		this.password = password;
		this.debugEnabled = debugEnabled;
		this.promptDetectionMode = promptDetectionMode == null ? PromptDetectionMode.RELAXED : promptDetectionMode;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

		startSession();
		primeLineCursor();
		startReceiverThread();
	}

	public Stream<String> execute(String commandStr) {
		var args = parseQuotedString(commandStr);
		if (args.isEmpty()) {
			throw new IllegalArgumentException("No command specified.");
		}
		var command = args.get(0);
		return executeCommand(command, args.subList(1, args.size()).toArray(new String[0]));
	}
	
	public Stream<String> executeCommand(String command, String... args) {
		if (!running.get()) {
			throw new IllegalStateException("Console is closed.");
		}

		synchronized (commandLock) {
			receivedLines.clear();
			var commandText = buildCommand(command, args);
			debug("COMMAND", "Submitting: " + commandText);

			var params = new LinkedHashMap<String, String>();
			params.put("ID", sessionId);
			params.put("COMMAND", commandText);
			var body = postForm("/SessionCommand", params);
			var result = parseElementText(body, "Result").orElse("");
			debug("COMMAND", "SessionCommand result payload: " + sanitize(result));

			final List<String> output;
			try {
				output = collectCommandOutput(commandText);
			} catch (InteractivePromptException e) {
				debug("COMMAND", "Interactive input prompt detected; recovering session before returning error.");
				attemptSessionRecovery(e);
				throw e;
			}
			if (!result.isBlank() && (!"OK".equalsIgnoreCase(result.trim()) || output.isEmpty())) {
				output.add("[Result] " + result);
			}
			return output.stream();
		}
	}

	public String prompt() {
		return prompt;
	}

	public List<String> modules() {
		return discoveredModules;
	}

	public HelpCatalog loadHelpCatalog() {
		var modules = discoveredModules;
		var entries = new ArrayList<HelpModule>();
		for (var module : modules) {
			var output = execute("help " + module).toList();
			entries.add(parseModuleHelp(module, output));
		}
		return new HelpCatalog(entries);
	}

	@Override
	public void close() {
		running.set(false);
		if (receiverThread != null) {
			receiverThread.interrupt();
			try {
				receiverThread.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void startSession() {
		var params = new LinkedHashMap<String, String>();
		username.ifPresent(value -> params.put("USER", value));
		password.ifPresent(value -> params.put("PASS", new String(value)));

		debug("SESSION", "Starting session against " + baseUrl + " with USER=" + username.orElse("<none>"));
		var response = postForm("/StartSession", params);
		sessionId = parseElementText(response, "SessionID")
				.orElseThrow(() -> new IllegalStateException("StartSession did not return SessionID."));
		prompt = parseElementText(response, "Prompt").orElse("");
		var modules = parseHelpModules(response);
		discoveredModules = List.copyOf(modules);
		debug("SESSION", "Session started id=" + sessionId + " prompt='" + prompt + "'");
		if (!modules.isEmpty()) {
			debug("SESSION", "Help modules discovered (" + modules.size() + "): " + modules);
		}
	}

	private void startReceiverThread() {
		receiverThread = new Thread(this::receiverLoop, "opensim-rest-console-receiver");
		receiverThread.setDaemon(true);
		receiverThread.start();
	}

	private void primeLineCursor() {
		try {
			var path = "/ReadResponses/" + urlEncode(sessionId) + "/";
			var response = postForm(path, Map.of("ID", sessionId));
			var lines = parseLines(response);
			var highestLineNumber = -1;
			for (var line : lines) {
				if (line.number > highestLineNumber) {
					highestLineNumber = line.number;
				}
			}
			if (highestLineNumber >= 0) {
				lastSeenLineNumber.set(highestLineNumber);
				debug("SESSION", "Primed line cursor at Number=" + highestLineNumber + " to skip stale backlog.");
			}
		} catch (RuntimeException e) {
			debug("SESSION", "Unable to prime line cursor: " + e.getMessage() + ". Continuing.");
		}
	}

	private void receiverLoop() {
		while (running.get()) {
			try {
				var path = "/ReadResponses/" + urlEncode(sessionId) + "/";
				var response = postForm(path, Map.of("ID", sessionId));
				var lines = parseLines(response);
				for (var line : lines) {
					if (line.number >= 0) {
						var previous = lastSeenLineNumber.get();
						if (line.number <= previous) {
							continue;
						}
						lastSeenLineNumber.set(line.number);
					}
					receivedLines.offer(line);
					debug("RECV", line.toDebugString());
				}
			} catch (RuntimeException e) {
				if (!running.get()) {
					break;
				}
				debug("RECV", "Polling failed: " + e.getMessage() + ". Attempting session recovery.");
				attemptSessionRecovery(e);
				try {
					Thread.sleep(RECOVERY_RETRY_DELAY_MS);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		debug("RECV", "Receiver thread stopped.");
	}

	private void attemptSessionRecovery(RuntimeException failure) {
		if (!running.get()) {
			return;
		}

		synchronized (commandLock) {
			if (!running.get()) {
				return;
			}
			try {
				debug("SESSION", "Re-establishing REST session after failure: " + failure.getMessage());
				receivedLines.clear();
				lastSeenLineNumber.set(-1);
				startSession();
				primeLineCursor();
				debug("SESSION", "REST session recovery complete.");
			} catch (RuntimeException recoveryError) {
				debug("SESSION", "Session recovery failed: " + recoveryError.getMessage());
			}
		}
	}

	private List<String> collectCommandOutput(String commandText) {
		var lines = new ArrayList<String>();
		var idleTimeout = 1200L;
		var maxWait = 40000L;
		var startedAt = System.nanoTime();
		var lastMessageAt = startedAt;
		var sawAny = false;
		var sawCommandEcho = false;
		var normalizedCommand = commandText == null ? "" : commandText.trim();
		var normalizedPrompt = prompt == null ? "" : prompt.trim();

		while (true) {
			var elapsedMs = millisSince(startedAt);
			if (elapsedMs > maxWait) {
				debug("COMMAND", "Command output wait reached max timeout " + maxWait + "ms.");
				break;
			}
			if (promptDetectionMode == PromptDetectionMode.RELAXED && sawAny && millisSince(lastMessageAt) > idleTimeout) {
				debug("COMMAND", "Command output appears idle after " + idleTimeout + "ms.");
				break;
			}

			try {
				var line = receivedLines.poll(250, TimeUnit.MILLISECONDS);
				if (line == null) {
					continue;
				}

				var lineText = line.message == null ? "" : line.message.trim();
				var isInputEcho = "true".equalsIgnoreCase(line.input);
				if (isInputEcho) {
					sawAny = true;
					lastMessageAt = System.nanoTime();
					sawCommandEcho = normalizedCommand.equals(lineText);
					debug("COMMAND", "Input echo line seen: '" + lineText + "' match=" + sawCommandEcho);
					continue;
				}

				if (!sawCommandEcho) {
					continue;
				}

				sawAny = true;
				lastMessageAt = System.nanoTime();
				if (looksLikeInteractiveInputPrompt(lineText, normalizedPrompt)) {
					throw new InteractivePromptException(commandText, line.message);
				}

				if (isPromptCompletionLine(line.prompt, line.command, lineText, normalizedPrompt)) {
					debug("COMMAND", "Prompt line reached; command output complete.");
					break;
				}

				if (!lineText.isEmpty()) {
					lines.add(line.message);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return lines;
	}

	private static boolean looksLikeInteractiveInputPrompt(String lineText, String expectedPrompt) {
		if (lineText == null) {
			return false;
		}

		var trimmed = lineText.trim();
		if (trimmed.isEmpty()) {
			return false;
		}

		if (!expectedPrompt.isEmpty() && expectedPrompt.equals(trimmed)) {
			return false;
		}

		var lower = trimmed.toLowerCase();
		if (trimmed.endsWith(":")) {
			if (trimmed.contains("[]:")) {
				return true;
			}

			if (trimmed.length() <= 160 && (lower.contains(" name")
					|| lower.startsWith("new region")
					|| lower.startsWith("name")
					|| lower.contains(" password")
					|| lower.startsWith("password")
					|| lower.contains(" email")
					|| lower.startsWith("email")
					|| lower.contains(" uuid")
					|| lower.startsWith("uuid")
					|| lower.contains(" model")
					|| lower.startsWith("model")
					|| lower.contains(" location")
					|| lower.startsWith("location")
					|| lower.contains(" enter ")
					|| lower.startsWith("enter "))) {
				return true;
			}
		}

		if (trimmed.endsWith("?") && (lower.contains("yes/no") || lower.contains("y/n"))) {
			return true;
		}

		return false;
	}

	private String postForm(String path, Map<String, String> params) {
		try {
			var payload = encodeForm(params);
			var target = URI.create(baseUrl + path);
			debug("HTTP", "POST " + target + " body=" + sanitize(payload));

			var request = HttpRequest.newBuilder(target)
					.timeout(Duration.ofSeconds(75))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString(payload))
					.build();

			var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			debug("HTTP", "Status " + response.statusCode() + ", body=" + sanitize(response.body()));
			if (response.statusCode() < 200 || response.statusCode() > 299) {
				throw new IllegalStateException("HTTP " + response.statusCode() + " from " + target);
			}
			return response.body();
		} catch (IOException e) {
			throw new IllegalStateException("REST call failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("REST call interrupted.", e);
		}
	}

	private static Optional<String> parseElementText(String xml, String tagName) {
		var document = parseXml(xml);
		var nodes = document.getElementsByTagName(tagName);
		if (nodes.getLength() == 0) {
			return Optional.empty();
		}
		return Optional.ofNullable(nodes.item(0).getTextContent()).map(String::trim);
	}

	private static List<ConsoleLine> parseLines(String xml) {
		var result = new ArrayList<ConsoleLine>();
		var document = parseXml(xml);
		var lines = document.getElementsByTagName("Line");
		for (int i = 0; i < lines.getLength(); i++) {
			var node = lines.item(i);
			if (node.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			var element = (Element) node;
			var number = parseIntAttribute(element, "Number", -1);
			var level = element.getAttribute("Level");
			var prompt = element.getAttribute("Prompt");
			var command = element.getAttribute("Command");
			var input = element.getAttribute("Input");
			var message = element.getTextContent() == null ? "" : element.getTextContent().trim();
			result.add(new ConsoleLine(number, level, prompt, command, input, message));
		}
		return result;
	}

	private static List<String> parseHelpModules(String xml) {
		var modules = new ArrayList<String>();
		var document = parseXml(xml);
		var nodes = document.getElementsByTagName("Module");
		for (int i = 0; i < nodes.getLength(); i++) {
			var text = nodes.item(i).getTextContent();
			if (text == null) {
				continue;
			}
			var trimmed = text.trim();
			if (trimmed.isEmpty() || modules.contains(trimmed)) {
				continue;
			}
			modules.add(trimmed);
		}
		return modules;
	}

	private static HelpModule parseModuleHelp(String moduleName, List<String> lines) {
		var commands = new ArrayList<HelpCommand>();
		for (var rawLine : lines) {
			if (rawLine == null) {
				continue;
			}
			for (var line : rawLine.split("\\R")) {
				var trimmed = line.trim();
				if (trimmed.startsWith("* ")) {
					trimmed = trimmed.substring(2).trim();
				}
				if (trimmed.isEmpty() || trimmed.startsWith("For more information,") || trimmed.startsWith("=== ")) {
					continue;
				}
				var delimiter = trimmed.indexOf(" - ");
				if (delimiter < 0) {
					continue;
				}
				var signature = trimmed.substring(0, delimiter).trim();
				var description = trimmed.substring(delimiter + 3).trim();
				commands.add(parseCommandSignature(signature, description));
			}
		}
		return new HelpModule(moduleName, commands);
	}

	private static HelpCommand parseCommandSignature(String signature, String description) {
		var tokens = tokenizeSignature(signature);
		if (tokens.isEmpty()) {
			return new HelpCommand(signature, signature, description, List.of());
		}
		var nameTokens = new ArrayList<String>();
		var args = new ArrayList<HelpArgument>();
		var argIndex = 0;
		var argsStarted = false;
		for (var token : tokens) {
			if (!argsStarted && isArgumentToken(token)) {
				argsStarted = true;
			}
			if (argsStarted) {
				for (var argument : parseArgumentTokens(token, argIndex)) {
					args.add(argument);
					argIndex++;
				}
			} else {
				nameTokens.add(token);
			}
		}
		var commandName = String.join(" ", nameTokens).trim();
		if (commandName.isEmpty()) {
			commandName = signature;
		}
		return new HelpCommand(signature, commandName, description, args);
	}

	private static List<String> tokenizeSignature(String signature) {
		if (signature == null || signature.isBlank()) {
			return List.of();
		}
		var tokens = new ArrayList<String>();
		var token = new StringBuilder();
		var squareDepth = 0;
		var angleDepth = 0;
		var inQuotes = false;
		var escaped = false;
		for (int i = 0; i < signature.length(); i++) {
			var c = signature.charAt(i);
			if (c == '"' && !escaped) {
				inQuotes = !inQuotes;
			}
			if (c == '\\' && !escaped) {
				escaped = true;
			} else {
				escaped = false;
			}
			if (Character.isWhitespace(c) && squareDepth == 0 && angleDepth == 0 && !inQuotes) {
				if (token.length() > 0) {
					tokens.add(token.toString());
					token.setLength(0);
				}
				continue;
			}
			token.append(c);
			if (c == '[') {
				squareDepth++;
			} else if (c == ']' && squareDepth > 0) {
				squareDepth--;
			} else if (c == '<') {
				angleDepth++;
			} else if (c == '>' && angleDepth > 0) {
				angleDepth--;
			}
		}
		if (token.length() > 0) {
			tokens.add(token.toString());
		}
		return tokens;
	}

	private static boolean isArgumentToken(String token) {
		return token.startsWith("<") || token.startsWith("[") || token.startsWith("--") || token.contains("<")
				|| token.contains("--");
	}

	private static List<HelpArgument> parseArgumentTokens(String token, int argOffset) {
		var arguments = new ArrayList<HelpArgument>();
		var rawArgs = collectRawArguments(token, false);
		var localIndex = 0;
		for (var raw : rawArgs) {
			for (var argument : normalizeRawArgument(raw, argOffset + localIndex)) {
				arguments.add(argument);
				localIndex++;
			}
		}
		return arguments;
	}

	private static List<RawArgumentToken> collectRawArguments(String text, boolean inheritedOptional) {
		var rawArgs = new ArrayList<RawArgumentToken>();
		int i = 0;
		var inQuotes = false;
		var escaped = false;
		while (i < text.length()) {
			char c = text.charAt(i);
			if (c == '"' && !escaped) {
				inQuotes = !inQuotes;
			}
			if (c == '\\' && !escaped) {
				escaped = true;
			} else {
				escaped = false;
			}

			if (!inQuotes && (Character.isWhitespace(c) || c == ',')) {
				i++;
				continue;
			}

			// Defensive: skip unmatched closing delimiters to avoid zero-progress loops.
			if (!inQuotes && (c == ']' || c == '>')) {
				i++;
				continue;
			}

			if (c == '"') {
				var startQuote = i;
				i++;
				var localEscaped = false;
				while (i < text.length()) {
					var qc = text.charAt(i);
					if (qc == '"' && !localEscaped) {
						i++;
						break;
					}
					if (qc == '\\' && !localEscaped) {
						localEscaped = true;
					} else {
						localEscaped = false;
					}
					i++;
				}
				var quotedToken = text.substring(startQuote, Math.min(i, text.length())).trim();
				if (!quotedToken.isEmpty()) {
					rawArgs.add(new RawArgumentToken(quotedToken, inheritedOptional));
				}
				inQuotes = false;
				continue;
			}
			if (c == '[') {
				int end = findMatching(text, i, '[', ']');
				if (end < 0) {
					var token = text.substring(i).trim();
					if (!token.isEmpty()) {
						rawArgs.add(new RawArgumentToken(token, true));
					}
					break;
				}
				var groupToken = text.substring(i, end + 1);
				var inner = groupToken.substring(1, groupToken.length() - 1);
				if (containsTopLevelSeparators(inner)) {
					// Anything inside [...] is optional by definition.
					rawArgs.addAll(collectRawArguments(inner, true));
				} else {
					rawArgs.add(new RawArgumentToken(groupToken, true));
				}
				i = end + 1;
				continue;
			}
			if (c == '<') {
				int end = findMatching(text, i, '<', '>');
				if (end < 0) {
					end = text.length() - 1;
				}
				rawArgs.add(new RawArgumentToken(text.substring(i, end + 1), inheritedOptional));
				i = end + 1;
				continue;
			}
			int start = i;
			while (i < text.length()) {
				char ch = text.charAt(i);
				if (Character.isWhitespace(ch) || ch == ',' || ch == '[' || ch == ']') {
					break;
				}
				i++;
			}
			if (i == start) {
				// Ensure forward progress even on unexpected syntax.
				i++;
				continue;
			}
			var token = text.substring(start, i).trim();

			// Combine forms like --default-user "User Name" into a single option token.
			if (!token.isEmpty() && token.startsWith("-")) {
				var lookahead = i;
				while (lookahead < text.length() && Character.isWhitespace(text.charAt(lookahead))) {
					lookahead++;
				}
				if (lookahead < text.length() && text.charAt(lookahead) == '"') {
					var quoteStart = lookahead;
					lookahead++;
					var localEscaped = false;
					while (lookahead < text.length()) {
						var qc = text.charAt(lookahead);
						if (qc == '"' && !localEscaped) {
							lookahead++;
							break;
						}
						if (qc == '\\' && !localEscaped) {
							localEscaped = true;
						} else {
							localEscaped = false;
						}
						lookahead++;
					}
					token = token + " " + text.substring(quoteStart, Math.min(lookahead, text.length())).trim();
					i = lookahead;
				}
			}

			if (!token.isEmpty()) {
				rawArgs.add(new RawArgumentToken(token, inheritedOptional));
			}
		}
		return rawArgs;
	}

	private static List<HelpArgument> normalizeRawArgument(RawArgumentToken raw, int argIndex) {
		var token = raw.token.trim();
		if ("|".equals(token)) {
			return List.of();
		}

		token = stripWrappingQuotes(token);
		if (token.isEmpty()) {
			return List.of();
		}

		if (token.startsWith("<") && token.endsWith(">")) {
			var inner = token.substring(1, token.length() - 1).trim();
			var enumValues = parseEnumValues(inner);
			if (!enumValues.isEmpty()) {
				return List.of(new HelpArgument(token, "arg" + argIndex, raw.optional, "positional", List.of(), enumValues,
						null));
			}
			if (inner.contains(",")) {
				var result = new ArrayList<HelpArgument>();
				for (var value : splitTopLevel(inner, ',')) {
					var cleaned = value.trim();
					if (!cleaned.isEmpty()) {
						var childToken = (raw.optional ? "[<" : "<") + cleaned + (raw.optional ? ">]" : ">");
						result.add(new HelpArgument(childToken, cleaned, raw.optional, "positional", List.of(), List.of(),
								null));
					}
				}
				return result;
			}
			return List.of(
					new HelpArgument(token, normalizeArgumentName(inner), raw.optional, "positional", List.of(), List.of(), null));
		}

		var unwrapped = stripWrappingQuotes(unwrapToken(token));
		var optionLike = unwrapped.contains("--") || unwrapped.startsWith("-");
		if (optionLike) {
			var options = splitTopLevel(unwrapped, '|');
			var cleaned = new ArrayList<String>();
			for (var option : options) {
				var o = option.trim();
				if (!o.isEmpty()) {
					cleaned.add(o);
				}
			}
			if (!cleaned.isEmpty()) {
				var canonical = cleaned.get(cleaned.size() - 1);
				var aliases = cleaned.size() > 1 ? cleaned.subList(0, cleaned.size() - 1) : List.<String>of();
				var optionPlaceholder = extractOptionPlaceholder(canonical);
				return List.of(new HelpArgument(token, normalizeOptionName(canonical), raw.optional, "option",
						List.copyOf(aliases), List.of(), optionPlaceholder));
			}
		}

		var enumValues = parseEnumValues(unwrapped);
		if (!enumValues.isEmpty()) {
			return List.of(new HelpArgument(token, "arg" + argIndex, raw.optional, "positional", List.of(), enumValues,
					null));
		}

		return List.of(
				new HelpArgument(token, normalizeArgumentName(unwrapped), raw.optional, "positional", List.of(), List.of(), null));
	}

	private static List<String> splitTopLevel(String text, char delimiter) {
		var parts = new ArrayList<String>();
		var current = new StringBuilder();
		var angleDepth = 0;
		var squareDepth = 0;
		var inQuotes = false;
		var escaped = false;
		for (int i = 0; i < text.length(); i++) {
			var c = text.charAt(i);
			if (c == '"' && !escaped) {
				inQuotes = !inQuotes;
			}
			if (c == '\\' && !escaped) {
				escaped = true;
			} else {
				escaped = false;
			}
			if (c == '<') {
				angleDepth++;
			} else if (c == '>' && angleDepth > 0) {
				angleDepth--;
			} else if (c == '[') {
				squareDepth++;
			} else if (c == ']' && squareDepth > 0) {
				squareDepth--;
			}
			if (c == delimiter && angleDepth == 0 && squareDepth == 0 && !inQuotes) {
				parts.add(current.toString());
				current.setLength(0);
				continue;
			}
			current.append(c);
		}
		parts.add(current.toString());
		return parts;
	}

	private static List<String> parseEnumValues(String text) {
		var trimmed = text == null ? "" : text.trim();
		if (trimmed.isEmpty()) {
			return List.of();
		}

		if (trimmed.contains("|")) {
			return cleanEnumValues(splitTopLevel(trimmed, '|'));
		}
		if (trimmed.contains("/")) {
			var slashValues = cleanEnumValues(splitTopLevel(trimmed, '/'));
			if (slashValues.size() >= 2 && slashValues.stream().allMatch(OpensimRESTConsole::isLikelyEnumAtom)) {
				return slashValues;
			}
		}
		return List.of();
	}

	private static List<String> cleanEnumValues(List<String> values) {
		var cleanedValues = new ArrayList<String>();
		for (var value : values) {
			var v = stripAngleDelimiters(stripWrappingQuotes(value.trim()));
			if (!v.isEmpty()) {
				cleanedValues.add(v);
			}
		}
		return cleanedValues;
	}

	private static boolean isLikelyEnumAtom(String value) {
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) {
				return false;
			}
		}
		return !value.isBlank();
	}

	private static boolean containsTopLevelSeparators(String text) {
		var angleDepth = 0;
		var squareDepth = 0;
		var inQuotes = false;
		var escaped = false;
		for (int i = 0; i < text.length(); i++) {
			var c = text.charAt(i);
			if (c == '"' && !escaped) {
				inQuotes = !inQuotes;
			}
			if (c == '\\' && !escaped) {
				escaped = true;
			} else {
				escaped = false;
			}
			if (c == '<') {
				angleDepth++;
			} else if (c == '>' && angleDepth > 0) {
				angleDepth--;
			} else if (c == '[') {
				squareDepth++;
			} else if (c == ']' && squareDepth > 0) {
				squareDepth--;
			}
			if ((Character.isWhitespace(c) || c == ',') && angleDepth == 0 && squareDepth == 0 && !inQuotes) {
				return true;
			}
		}
		return false;
	}

	private static int findMatching(String text, int start, char open, char close) {
		var depth = 0;
		for (int i = start; i < text.length(); i++) {
			var c = text.charAt(i);
			if (c == open) {
				depth++;
			} else if (c == close) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String unwrapToken(String token) {
		var value = token == null ? "" : token.trim();
		if ((value.startsWith("[") && value.endsWith("]")) || (value.startsWith("<") && value.endsWith(">"))) {
			value = value.substring(1, value.length() - 1);
		}
		return value;
	}

	private static String normalizeArgumentName(String token) {
		var value = stripAngleDelimiters(stripWrappingQuotes(token == null ? "" : token.trim()));
		if (value.startsWith("--")) {
			return value;
		}
		if (value.startsWith("-")) {
			return value;
		}
		return value.replace("<", "").replace(">", "").trim();
	}

	private static String normalizeOptionName(String option) {
		var value = stripWrappingQuotes(option == null ? "" : option.trim());
		while (value.startsWith("-")) {
			value = value.substring(1);
		}
		var whitespaceIdx = value.indexOf(' ');
		if (whitespaceIdx > 0) {
			value = value.substring(0, whitespaceIdx);
		}
		var equalsIdx = value.indexOf('=');
		if (equalsIdx > 0) {
			value = value.substring(0, equalsIdx);
		}
		return value;
	}

	private static String extractOptionPlaceholder(String option) {
		var value = stripWrappingQuotes(option == null ? "" : option.trim());
		var equalsIdx = value.indexOf('=');
		String placeholder = null;
		if (equalsIdx >= 0 && equalsIdx < value.length() - 1) {
			placeholder = value.substring(equalsIdx + 1).trim();
		} else {
			var whitespaceIdx = value.indexOf(' ');
			if (whitespaceIdx > 0 && whitespaceIdx < value.length() - 1) {
				placeholder = value.substring(whitespaceIdx + 1).trim();
			}
		}

		if (placeholder == null || placeholder.isEmpty()) {
			return null;
		}

		placeholder = stripWrappingQuotes(placeholder);
		if ((placeholder.startsWith("<") && placeholder.endsWith(">"))
				|| (placeholder.startsWith("[") && placeholder.endsWith("]"))) {
			placeholder = placeholder.substring(1, placeholder.length() - 1).trim();
		}
		return placeholder.isEmpty() ? null : placeholder;
	}

	private static String stripWrappingQuotes(String value) {
		if (value == null) {
			return "";
		}

		var trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1).trim();
		}

		return trimmed;
	}

	private static String stripAngleDelimiters(String value) {
		if (value == null) {
			return "";
		}

		var trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("<") && trimmed.endsWith(">")) {
			return trimmed.substring(1, trimmed.length() - 1).trim();
		}

		return trimmed;
	}

	private static Document parseXml(String xml) {
		try {
			if (xml == null || xml.isBlank()) {
				throw new IllegalStateException("Failed to parse XML: empty response body.");
			}
			var factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			var builder = factory.newDocumentBuilder();
			builder.setErrorHandler(new ErrorHandler() {
				@Override
				public void warning(SAXParseException exception) {
					// Swallow parser warnings to keep logs readable; caller handles parse failures.
				}

				@Override
				public void error(SAXParseException exception) {
					// Swallow parser errors to avoid repeated stderr spam from the XML parser.
				}

				@Override
				public void fatalError(SAXParseException exception) {
					// Swallow fatal parser messages; parse() still throws and is handled below.
				}
			});
			return builder.parse(new InputSource(new StringReader(xml)));
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse XML: " + sanitize(xml), e);
		}
	}

	private static int parseIntAttribute(Element element, String attribute, int defaultValue) {
		var raw = element.getAttribute(attribute);
		if (raw == null || raw.isBlank()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(raw);
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	private static String buildCommand(String command, String[] args) {
		var out = new StringBuilder(command);
		for (var arg : args) {
			out.append(' ').append(quoteArg(arg));
		}
		return out.toString();
	}

	private static String quoteArg(String arg) {
		if (arg == null) {
			return "";
		}
		if (arg.indexOf(' ') < 0 && arg.indexOf('"') < 0) {
			return arg;
		}
		return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private static String encodeForm(Map<String, String> params) {
		var form = new StringBuilder();
		for (var entry : params.entrySet()) {
			if (form.length() > 0) {
				form.append('&');
			}
			form.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
		}
		return form.toString();
	}

	private static String urlEncode(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	private static String normalizeUrl(String url) {
		if (url.endsWith("/")) {
			return url.substring(0, url.length() - 1);
		}
		return url;
	}

	private static long millisSince(long startNanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}

	private void debug(String channel, String message) {
		if (debugEnabled) {
			System.err.println("[OpensimRESTConsole][" + channel + "] " + message);
		}
	}

	private static String sanitize(String text) {
		if (text == null) {
			return "<null>";
		}
		var compact = text.replace('\n', ' ').replace('\r', ' ').trim();
		if (compact.length() <= 300) {
			return compact;
		}
		return compact.substring(0, 300) + "...";
	}

	private static boolean isPromptCompletionLine(String promptFlag, String commandFlag, String lineText,
			String expectedPrompt) {
		if ("true".equalsIgnoreCase(promptFlag) || "true".equalsIgnoreCase(commandFlag)) {
			return true;
		}
		return !expectedPrompt.isEmpty() && expectedPrompt.equals(lineText == null ? "" : lineText.trim());
	}

	private static final class ConsoleLine {
		private final int number;
		private final String level;
		private final String prompt;
		private final String command;
		private final String input;
		private final String message;

		private ConsoleLine(int number, String level, String prompt, String command, String input, String message) {
			this.number = number;
			this.level = level == null ? "" : level;
			this.prompt = prompt == null ? "" : prompt;
			this.command = command == null ? "" : command;
			this.input = input == null ? "" : input;
			this.message = message == null ? "" : message;
		}

		private String toDebugString() {
			return "Line#" + number + " level=" + level + " prompt='" + prompt + "' command='" + command + "' input='"
					+ input + "' message='" + message + "'";
		}

	}

	private static final class InteractivePromptException extends IllegalStateException {
		private static final long serialVersionUID = 1L;

		private InteractivePromptException(String commandText, String promptText) {
			super("Command '" + commandText + "' appears to be waiting for interactive input ('"
					+ (promptText == null ? "" : promptText.trim())
					+ "'). This usually means one or more required parameters were omitted.");
		}
	}

	static HelpCommand parseHelpCommandLineForTest(String line) {
		if (line == null) {
			throw new IllegalArgumentException("Line must not be null.");
		}
		var delimiter = line.indexOf(" - ");
		if (delimiter < 0) {
			throw new IllegalArgumentException("Line does not contain command delimiter ' - '.");
		}
		var signature = line.substring(0, delimiter).trim();
		var description = line.substring(delimiter + 3).trim();
		return parseCommandSignature(signature, description);
	}

	static boolean isPromptCompletionLineForTest(String promptFlag, String commandFlag, String lineText,
			String expectedPrompt) {
		return isPromptCompletionLine(promptFlag, commandFlag, lineText, expectedPrompt == null ? "" : expectedPrompt);
	}

	public static final class HelpCatalog {
		private final List<HelpModule> modules;

		public HelpCatalog(List<HelpModule> modules) {
			this.modules = modules == null ? List.of() : List.copyOf(modules);
		}

		public List<HelpModule> modules() {
			return modules;
		}
	}

	public static final class HelpModule {
		private final String name;
		private final List<HelpCommand> commands;

		public HelpModule(String name, List<HelpCommand> commands) {
			this.name = name;
			this.commands = commands == null ? List.of() : List.copyOf(commands);
		}

		public String name() {
			return name;
		}

		public List<HelpCommand> commands() {
			return commands;
		}
	}

	public static final class HelpCommand {
		private final String signature;
		private final String name;
		private final String description;
		private final List<HelpArgument> arguments;

		public HelpCommand(String signature, String name, String description, List<HelpArgument> arguments) {
			this.signature = signature;
			this.name = name;
			this.description = description;
			this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
		}

		public String signature() {
			return signature;
		}

		public String name() {
			return name;
		}

		public String description() {
			return description;
		}

		public List<HelpArgument> arguments() {
			return arguments;
		}
	}

	public static final class HelpArgument {
		private final String token;
		private final String name;
		private final boolean optional;
		private final String kind;
		private final List<String> aliases;
		private final List<String> values;
		private final String option;

		public HelpArgument(String token, String name, boolean optional, String kind, List<String> aliases,
				List<String> values, String option) {
			this.token = token;
			this.name = name;
			this.optional = optional;
			this.kind = kind;
			this.aliases = aliases == null ? List.of() : List.copyOf(aliases);
			this.values = values == null ? List.of() : List.copyOf(values);
			this.option = option;
		}

		public String token() {
			return token;
		}

		public String name() {
			return name;
		}

		public boolean optional() {
			return optional;
		}

		public String kind() {
			return kind;
		}

		public List<String> aliases() {
			return aliases;
		}

		public List<String> values() {
			return values;
		}

		public String option() {
			return option;
		}
	}

	private static final class RawArgumentToken {
		private final String token;
		private final boolean optional;

		private RawArgumentToken(String token, boolean optional) {
			this.token = token;
			this.optional = optional;
		}
	}
	

	private static List<String> parseQuotedString(String command) {
		var args = new ArrayList<String>();
		var escaped = false;
		var quoted = false;
		var word = new StringBuilder();
		for (int i = 0; i < command.length(); i++) {
			char c = command.charAt(i);
			if (escaped) {
				word.append(c);
				escaped = false;
				continue;
			}
			if (c == '\\') {
				escaped = true;
				continue;
			}
			if (c == '"') {
				quoted = !quoted;
				continue;
			}
			if (Character.isWhitespace(c) && !quoted) {
				if (word.length() > 0) {
					args.add(word.toString());
					word.setLength(0);
				}
				continue;
			}
			word.append(c);
		}
		if (escaped) {
			throw new IllegalArgumentException("Invalid escape.");
		}
		if (quoted) {
			throw new IllegalArgumentException("Unbalanced quotes.");
		}
		if (word.length() > 0) {
			args.add(word.toString());
		}
		return args;
	}
}

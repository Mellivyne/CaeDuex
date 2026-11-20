package com.cheatprogram.ai;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One local mailbox per explicitly started session. Only the game can perform an action. */
public final class AiGameplayExchange implements AutoCloseable {
    private static final int MAX_BYTES = 256 * 1024;
    private static final long SESSION_TIME = 30 * 60_000L;
    private static final long LEASE_TIME = 20_000;
    private static final long COMMAND_AGE = 30_000;
    private static final int MESSAGE_LIMIT = 16;
    private static final int MESSAGE_LENGTH = 1_000;
    private static final Set<AiGameplayExchange> SESSIONS = new HashSet<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CaeDuex gameplay MCP");
        thread.setDaemon(true);
        return thread;
    });
    record Command(UUID sequence, long submittedAt, String action, JsonObject arguments) {}
    private record Mailbox(Path root, String configuration) {}

    private final UUID id = UUID.randomUUID();
    private final String kind, language;
    private String name, request;
    private JsonObject character;
    private final JsonArray tools;
    private final AiGameplayBackground background;
    private final long deadline;
    private final Map<UUID, Long> handled = new HashMap<>();
    private final ArrayDeque<JsonObject> messages = new ArrayDeque<>();
    private long messageSequence;
    private final CompletableFuture<Mailbox> mailbox;
    private CompletableFuture<Command> inbound;
    private volatile boolean closed;
    private boolean disposed;
    private CompletableFuture<?> cleanup = CompletableFuture.completedFuture(null);
    private Command active;
    private long nextPoll, nextSnapshot;
    private boolean lastReady;
    private String lastStatus = "ui.ai.mcp.preparing";

    AiGameplayExchange(String kind, String request, String name, JsonArray tools, JsonObject observation) {
        this(kind, request, name, tools, observation, new JsonObject());
    }

    AiGameplayExchange(String kind, String request, String name, JsonArray tools, JsonObject observation, JsonObject character) {
        this.kind = kind;
        this.deadline = kind.equals("player") ? Long.MAX_VALUE : System.currentTimeMillis() + SESSION_TIME;
        this.request = request == null ? "" : request;
        this.name = name == null ? "" : name;
        this.language = AiDesignPrompts.supportedLanguage(Lang.language());
        this.character = character.deepCopy();
        this.tools = tools.deepCopy();
        background = AiGameplayBackground.hold();
        JsonObject snapshot = snapshot(false, observation, System.currentTimeMillis());
        mailbox = CompletableFuture.supplyAsync(() -> {
            try {
                Path root = initializeDirectory();
                write(path(root, "session"), snapshot);
                return new Mailbox(root, configuration(root));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        }, IO);
        SESSIONS.add(this);
    }

    public CompletableFuture<String> prompt() {
        String instructions = AiDesignPrompts.gameplayInstructions(kind, character)
                + (request.isBlank() ? "" : "\n\n" + Lang.t("ui.ai.mcp.task") + "\n" + request);
        return mailbox.thenApply(box -> {
            if (closed) throw new java.util.concurrent.CancellationException();
            return instructions + "\n```json\n" + box.configuration + "\n```";
        });
    }

    void updateCharacter(JsonObject profile) {
        character = profile.deepCopy();
        name = character.get("name").getAsString();
        nextSnapshot = 0;
    }

    void reportPlayerTask(JsonObject observation) {
        request = "";
        // A new client reading immediately after the reply must not pick up the completed task again.
        publishSnapshot(mailbox.join(), snapshot(false, observation, System.currentTimeMillis()));
        complete(true, Lang.t("ui.ai.mcp.taskReported"), observation);
    }

    void receivePlayerMessage(String message) {
        if (closed || !kind.equals("player") || message.isBlank()) return;
        JsonObject entry = new JsonObject();
        entry.addProperty("sequence", ++messageSequence);
        int end = message.offsetByCodePoints(0, Math.min(MESSAGE_LENGTH, message.codePointCount(0, message.length())));
        entry.addProperty("text", message.substring(0, end));
        if (messages.size() == MESSAGE_LIMIT) messages.removeFirst();
        messages.addLast(entry);
        nextSnapshot = 0;
    }

    boolean expired() { return closed || System.currentTimeMillis() >= deadline || mailbox.isCompletedExceptionally(); }

    /** Called on the client thread. There is at most one queued or executing action. */
    void tick(boolean ready, Supplier<JsonObject> observe, Consumer<Command> execute) {
        tick(ready, ready ? "ui.ai.mcp.ready" : "ui.ai.mcp.preparing", observe, execute);
    }

    void tick(boolean ready, String status, Supplier<JsonObject> observe, Consumer<Command> execute) {
        if (expired() || !mailbox.isDone()) return;
        long now = System.currentTimeMillis();
        Mailbox box = mailbox.join();
        boolean available = ready && active == null;
        String currentStatus = active == null ? status : "ui.ai.mcp.busy";
        if (now >= nextSnapshot || available != lastReady || !currentStatus.equals(lastStatus)) {
            lastReady = available;
            lastStatus = currentStatus;
            nextSnapshot = now + 2_000;
            publishSnapshot(box, snapshot(available, observe.get(), now));
        }
        if (!available) return;
        if (inbound != null && inbound.isDone()) {
            Command command = inbound.getNow(null);
            inbound = null;
            if (command == null) return;
            handled.values().removeIf(previous -> now - previous > COMMAND_AGE);
            active = command;
            if (now - command.submittedAt > COMMAND_AGE) {
                complete(false, Lang.t("ui.ai.mcp.invalid"), observe.get());
            } else if (handled.putIfAbsent(command.sequence, now) != null) {
                active = null;
            } else if (!command.action.equals("finish") && !knownAction(command.action)) {
                complete(false, Lang.t("ui.ai.mcp.unknown"), observe.get());
            } else {
                try { execute.accept(command); }
                catch (RuntimeException invalid) { complete(false, Lang.t("ui.ai.mcp.invalid"), observe.get()); }
            }
        } else if (inbound == null && now >= nextPoll) {
            nextPoll = now + 250;
            inbound = CompletableFuture.supplyAsync(() -> readCommand(box.root), IO);
        }
    }

    private void publishSnapshot(Mailbox box, JsonObject state) {
        IO.execute(() -> {
            if (!closed) try { write(path(box.root, "session"), state); }
            catch (IOException failure) { closed = true; }
        });
    }

    void complete(boolean ok, String message, JsonObject observation) {
        if (active == null || closed) return;
        Command command = active;
        active = null;
        nextSnapshot = 0;
        JsonObject reply = new JsonObject();
        reply.addProperty("id", id.toString());
        reply.addProperty("sequence", command.sequence.toString());
        reply.addProperty("ok", ok);
        reply.addProperty("message", message == null ? "" : message);
        reply.add("observation", observation.deepCopy());
        Mailbox box = mailbox.join();
        IO.execute(() -> {
            try { write(path(box.root, "reply"), reply); }
            catch (IOException failure) { closed = true; }
        });
    }

    @Override public void close() {
        if (disposed) return;
        disposed = true;
        SESSIONS.remove(this);
        background.close();
        closed = true;
        cleanup = mailbox.handleAsync((box, failure) -> {
            if (box == null) return null;
            for (String prefix : Set.of("session", "command")) {
                try { Files.deleteIfExists(path(box.root, prefix)); }
                catch (IOException ignored) { }
            }
            // The process can still collect the final reply after the session closes.
            return null;
        }, IO);
    }

    /** Called on the client thread before Minecraft saves its options and exits. */
    public static void shutdown() {
        var sessions = Set.copyOf(SESSIONS);
        sessions.forEach(AiGameplayExchange::close);
        try {
            CompletableFuture.allOf(sessions.stream().map(session -> session.cleanup)
                    .toArray(CompletableFuture[]::new)).get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) { }
    }

    private boolean knownAction(String action) {
        JsonArray names = tools.get(0).getAsJsonObject().getAsJsonObject("function")
                .getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("action").getAsJsonArray("enum");
        return names.contains(new JsonPrimitive(action));
    }

    private JsonObject snapshot(boolean ready, JsonObject observation, long now) {
        JsonObject state = new JsonObject();
        state.addProperty("id", id.toString());
        state.addProperty("kind", kind);
        state.addProperty("language", language);
        state.addProperty("request", request);
        state.addProperty("name", name);
        state.add("character", character.deepCopy());
        state.add("tools", tools);
        state.add("observation", observation.deepCopy());
        state.addProperty("ready", ready);
        state.addProperty("status", Lang.t(lastStatus));
        state.addProperty("expiresAt", Math.min(deadline, now + LEASE_TIME));
        if (kind.equals("player")) {
            JsonArray inbox = new JsonArray();
            messages.forEach(inbox::add);
            state.add("messages", inbox);
            state.addProperty("latestSequence", messageSequence);
        }
        return state;
    }

    private Path path(Path root, String prefix) { return root.resolve(prefix + "-" + id + ".json"); }

    private Command readCommand(Path root) {
        Path file = path(root, "command");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            byte[] bytes;
            try (var source = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = source.readNBytes(MAX_BYTES + 1);
            } finally {
                Files.deleteIfExists(file);
            }
            if (closed || bytes.length > MAX_BYTES) return null;
            JsonObject json = AiDesignExchange.parseResult(new String(bytes, StandardCharsets.UTF_8));
            if (!id.toString().equals(json.get("id").getAsString())) return null;
            String rawSequence = json.get("sequence").getAsString();
            UUID sequence = UUID.fromString(rawSequence);
            if (sequence.version() != 4 || !sequence.toString().equals(rawSequence)) return null;
            long submitted = json.get("submittedAt").getAsLong(), now = System.currentTimeMillis();
            if (submitted > now + 2_000) return null;
            String action = json.get("action").getAsString();
            JsonObject arguments = json.getAsJsonObject("arguments");
            if (action.length() > 64 || arguments == null) return null;
            return new Command(sequence, submitted, action, arguments);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static Path initializeDirectory() throws IOException {
        if (!Config.persistenceAllowed()) throw new IOException("Game configuration is unavailable");
        Path root = Config.directory().resolve("ai-gameplay-mcp").toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Invalid gameplay MCP directory");
        root = root.toRealPath();
        for (String name : Set.of("gameplay_mcp.py", "design_mcp.py")) {
            try (var source = AiGameplayExchange.class.getResourceAsStream("/assets/cheatprogram/ai/" + name)) {
                if (source == null) throw new IOException("Missing MCP resource");
                write(root.resolve(name), source.readAllBytes());
            }
        }
        long stale = System.currentTimeMillis() - SESSION_TIME;
        try (var files = Files.newDirectoryStream(root)) {
            for (Path file : files) {
                if (file.getFileName().toString().matches("(session|command|reply)-[0-9a-f-]{36}\\.json|lock-[0-9a-f-]{36}\\.lck")
                        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        String filename = file.getFileName().toString();
                        int end = filename.lastIndexOf('.');
                        Path session = root.resolve("session-" + filename.substring(end - 36, end) + ".json");
                        if (Files.getLastModifiedTime(file).toMillis() < stale
                                && (!Files.isRegularFile(session, LinkOption.NOFOLLOW_LINKS)
                                || Files.getLastModifiedTime(session).toMillis() < stale)) Files.deleteIfExists(file);
                    }
                    catch (IOException ignored) { }
                }
            }
        }
        return root;
    }

    private String configuration(Path root) {
        JsonArray args = new JsonArray();
        for (String argument : new String[]{"-u", root.resolve("gameplay_mcp.py").toString(), "--root", root.toString(), "--session", id.toString()}) {
            args.add(argument);
        }
        JsonObject server = new JsonObject();
        server.addProperty("command", "python");
        server.add("args", args);
        JsonObject servers = new JsonObject();
        servers.add("caeduex-gameplay", server);
        JsonObject config = new JsonObject();
        config.add("mcpServers", servers);
        return new GsonBuilder().setPrettyPrinting().create().toJson(config);
    }

    private static void write(Path target, JsonObject data) throws IOException {
        write(target, data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES || Files.isSymbolicLink(target)) throw new IOException("Invalid gameplay MCP payload");
        Path temporary = Files.createTempFile(target.getParent(), ".gameplay-", ".tmp");
        try {
            Files.write(temporary, bytes);
            for (int attempt = 0; ; attempt++) {
                try {
                    try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                    catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
                    break;
                } catch (FileSystemException unavailable) {
                    if (attempt == 4) throw unavailable;
                    try { Thread.sleep(25L << attempt); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

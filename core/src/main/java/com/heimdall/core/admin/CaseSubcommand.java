package com.heimdall.core.admin;

import com.heimdall.core.command.CommandSource;
import com.heimdall.core.http.ApiError;
import com.heimdall.core.punish.HiddenPunishments;
import com.heimdall.core.text.Msg;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

/**
 * {@code /hd case note <number> <text>} - add a note to a Heimdall case from in game.
 *
 * <p>The case number is the one the dashboard and Discord show. The note is written as the player
 * who typed it, by their UUID; the bot looks up their linked Discord account itself rather than
 * being told one.
 *
 * <h2>What it can reach</h2>
 *
 * <p>Gated on {@code heimdall.admin} at the door, like every verb here. The bot narrows it further:
 * the note can only land on a Minecraft case, and a hidden one only when the player holds
 * {@code heimdall.punishments.hidden}, which is the only time {@code includeHidden} is sent. A case
 * outside that answers "No such case.", the same as one that does not exist.
 *
 * <p>Players only. The console has no account to write a note as, and a note signed by nobody is
 * a note nobody can stand behind.
 */
final class CaseSubcommand implements AdminSubcommand {

    /** The bot's limit; checked here too so a long note fails before a round trip. */
    static final int MAX_NOTE_CHARS = 2000;

    @Override
    public String name() {
        return "case";
    }

    @Override
    public String usage() {
        return "note <number> <text>";
    }

    @Override
    public String description() {
        return "add a note to a Heimdall case";
    }

    @Override
    public void run(final CommandSource source, List<String> args, final AdminContext context) {
        String usageLine = "§7Usage: §f/" + context.label() + " case " + usage();
        if (args.size() < 3 || !"note".equals(args.get(0).toLowerCase(Locale.ROOT))) {
            source.sendMessage(Msg.legacy(usageLine));
            return;
        }
        final int number = parseCaseNumber(args.get(1));
        if (number < 1) {
            source.sendMessage(Msg.legacy("§c'" + args.get(1) + "' is not a case number. " + usageLine));
            return;
        }
        final UUID author = source.uuid();
        if (!source.isPlayer() || author == null) {
            source.sendMessage(Msg.legacy("§cOnly a player can add a case note: the note is signed "
                    + "with the account that wrote it."));
            return;
        }
        final String text = String.join(" ", args.subList(2, args.size())).trim();
        if (text.isEmpty()) {
            source.sendMessage(Msg.legacy(usageLine));
            return;
        }
        if (text.length() > MAX_NOTE_CHARS) {
            source.sendMessage(Msg.legacy("§cA note is at most " + MAX_NOTE_CHARS
                    + " characters; that one is " + text.length() + "."));
            return;
        }
        final boolean includeHidden = source.hasPermission(HiddenPunishments.PERMISSION);
        final String name = source.name();
        source.sendMessage(Msg.legacy("§7Adding a note to case §f#" + number + "§7…"));
        // Not handed to context.async: the API call is already off this thread, and only argument
        // checks above it can throw here.
        context.runtime().api()
                .addCaseNote(number, author.toString(), name, text, includeHidden)
                .whenComplete(new BiConsumer<Object, Throwable>() {
                    @Override
                    public void accept(Object ignored, Throwable failure) {
                        if (failure == null) {
                            source.sendMessage(Msg.legacy("§aNote added to case §f#" + number + "§a."));
                        } else {
                            source.sendMessage(Msg.legacy(
                                    "§cCould not add the note: " + describe(failure)));
                        }
                    }
                });
    }

    @Override
    public List<String> complete(CommandSource source, List<String> args, AdminContext context) {
        return args.size() <= 1
                ? Collections.singletonList("note")
                : Collections.<String>emptyList();
    }

    /** A case number as typed, {@code 1482} or {@code #1482}; {@code -1} for anything else. */
    static int parseCaseNumber(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (!value.matches("\\d{1,9}")) {
            return -1;
        }
        int number = Integer.parseInt(value);
        return number >= 1 ? number : -1;
    }

    /**
     * The sentence to show the player. The bot's own words when it refused ("No such case."),
     * otherwise the failure's message: an unreachable bot is worth saying as such.
     */
    static String describe(Throwable failure) {
        Throwable root = failure;
        for (int depth = 0; depth < 16; depth++) {
            if (!(root instanceof CompletionException) && !(root instanceof ExecutionException)) {
                break;
            }
            if (root.getCause() == null || root.getCause() == root) {
                break;
            }
            root = root.getCause();
        }
        if (root instanceof ApiError) {
            String detail = ((ApiError) root).detail().trim();
            if (!detail.isEmpty()) {
                return detail;
            }
        }
        String message = root == null ? null : root.getMessage();
        return message == null || message.trim().isEmpty()
                ? (root == null ? "the request failed" : root.getClass().getSimpleName())
                : message.trim();
    }
}

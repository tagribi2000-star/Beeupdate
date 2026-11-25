package me.beeupdate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Honey Coin storage. Pure Java (no Bukkit) so it can be tested on its own. */
public final class Ledger {
    public record Account(UUID id, String name, long balance) {}

    private final Path file;
    private final long maxBalance;
    private final Map<UUID, Account> accounts = new HashMap<>();
    private final Object writeLock = new Object();
    private long version, savedVersion;

    public Ledger(Path file, long maxBalance) throws IOException {
        this.file = file;
        this.maxBalance = maxBalance;
        load();
    }

    private Path sibling(String suffix) {
        return file.resolveSibling(file.getFileName() + suffix);
    }

    /** A corrupt file throws, so that it is never silently overwritten with empty data. */
    private void load() throws IOException {
        Path source = Files.exists(file) ? file : sibling(".bak");
        if (!Files.exists(source)) return;
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", -1);
            try {
                UUID id = UUID.fromString(p[0]);
                long balance = Long.parseLong(p[1]);
                if (balance < 0) throw new IllegalArgumentException("negative balance");
                accounts.put(id, new Account(id, p[2], balance));
            } catch (RuntimeException e) {
                throw new IOException("Corrupt line in " + source + ": " + line, e);
            }
        }
    }

    public synchronized long balance(UUID id) {
        Account a = accounts.get(id);
        return a == null ? 0 : a.balance();
    }

    public synchronized void touch(UUID id, String name) {
        Account a = accounts.get(id);
        if (a == null || !a.name().equals(name)) put(id, name, a == null ? 0 : a.balance());
    }

    private void put(UUID id, String name, long balance) {
        accounts.put(id, new Account(id, name, balance));
        version++;
    }

    private String nameOf(UUID id) {
        Account a = accounts.get(id);
        return a == null ? "?" : a.name();
    }

    public synchronized boolean deposit(UUID id, long amount) {
        if (amount <= 0 || amount > maxBalance - balance(id)) return false;
        put(id, nameOf(id), balance(id) + amount);
        return true;
    }

    public synchronized boolean withdraw(UUID id, long amount) {
        if (amount <= 0 || balance(id) < amount) return false;
        put(id, nameOf(id), balance(id) - amount);
        return true;
    }

    public synchronized boolean transfer(UUID from, UUID to, long amount) {
        if (from.equals(to) || amount <= 0 || balance(from) < amount
                || amount > maxBalance - balance(to)) return false;
        withdraw(from, amount);
        deposit(to, amount);
        return true;
    }

    public synchronized Optional<Account> findByName(String name) {
        return accounts.values().stream().filter(a -> a.name().equalsIgnoreCase(name)).findFirst();
    }

    public synchronized List<Account> top(int n) {
        return accounts.values().stream().filter(a -> a.balance() > 0)
                .sorted(Comparator.comparingLong(Account::balance).reversed().thenComparing(Account::name))
                .limit(n).toList();
    }

    /** Atomic write: temp file, fsync, keep a backup, then rename over the old file. */
    public void save() throws IOException {
        synchronized (writeLock) {
            StringBuilder out = new StringBuilder();
            long snapshot;
            synchronized (this) {
                if (version == savedVersion) return;
                snapshot = version;
                for (Account a : accounts.values())
                    out.append(a.id()).append('\t').append(a.balance()).append('\t').append(a.name()).append('\n');
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = sibling(".tmp");
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer data = StandardCharsets.UTF_8.encode(out.toString());
                while (data.hasRemaining()) ch.write(data);
                ch.force(true);
            }
            if (Files.exists(file)) Files.copy(file, sibling(".bak"), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            synchronized (this) { savedVersion = snapshot; }
        }
    }
}

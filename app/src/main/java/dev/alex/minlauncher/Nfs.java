package dev.alex.minlauncher;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Just enough of an NFSv3 client (RFC 1813, ONC RPC over TCP, RFC 5531) to
 * read files from an export that needs no authentication: the portmapper
 * finds mountd and nfsd, MOUNT gives the root file handle, then LOOKUP and
 * READ. Authentication is AUTH_SYS, the only kind a plain Linux export
 * checks without Kerberos: the client states a uid and gid, and the server
 * applies file permissions to them. There is no password: any client can
 * claim any uid, so the real limit is the export's own (ro, the allowed
 * hosts, and all_squash to an unprivileged anonuid/anongid). See README.
 * The default identity is nobody, never root.
 *
 * A Linux export is "secure" by default: it only answers clients on a port
 * below 1024, which an unrooted app cannot bind. Such exports need the
 * {@code insecure} option. A reserved port is still tried first, so it works
 * as root too.
 *
 * Blocking and not thread-safe; plain Java, so it runs on the JVM as well.
 */
final class Nfs implements Closeable {

    static final int PORTMAP = 100000, MOUNT = 100005, NFS = 100003;
    static final int NFS_PORT = 2049;
    private static final int TIMEOUT_MS = 10_000;
    private static final int READ_CHUNK = 256 * 1024;
    private static final int NF3REG = 1, NF3DIR = 2;
    private static final int FATTR3 = 84;   // bytes

    /** "host:/export/path", or "nfs://host/export/path", split. */
    static final class Spec {
        final String host, path;
        private Spec(String host, String path) { this.host = host; this.path = path; }

        static Spec parse(String s) {
            String t = s == null ? "" : s.trim();
            if (t.startsWith("nfs://")) {
                t = t.substring(6);
                int slash = t.indexOf('/');
                if (slash > 0) t = t.substring(0, slash) + ":" + t.substring(slash);
            }
            int colon = t.indexOf(':');
            if (colon <= 0 || colon == t.length() - 1 || t.charAt(colon + 1) != '/') {
                throw new IllegalArgumentException("\"" + s + "\" is not host:/path");
            }
            String path = t.substring(colon + 1).replaceAll("/+", "/");
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return new Spec(t.substring(0, colon), path);
        }

        @Override public String toString() { return host + ":" + path; }
    }

    /** A file or directory: its handle, and size and modification time from its attributes. */
    static final class Node {
        final byte[] handle;
        final int type;
        final long size, mtimeMs;
        Node(byte[] handle, int type, long size, long mtimeMs) {
            this.handle = handle; this.type = type; this.size = size; this.mtimeMs = mtimeMs;
        }
        boolean isFile() { return type == NF3REG; }
    }

    /** An NFS or MOUNT status other than OK. */
    static final class StatusException extends IOException {
        final int status;
        StatusException(String what, int status) {
            super(what + ": " + describe(status));
            this.status = status;
        }
    }

    private final Rpc nfs;
    private final Node root;
    private final Map<String, Node> dirs = new HashMap<>();

    private Nfs(Rpc nfs, Node root) {
        this.nfs = nfs;
        this.root = root;
    }

    /** AUTH_SYS credentials: who the server is told the client is. */
    static final class Auth {
        static final int NOBODY = 65534;
        final int uid, gid;
        final int[] gids;   // supplementary groups, at most 16
        Auth(int uid, int gid, int[] gids) {
            if (uid < 0 || gid < 0) throw new IllegalArgumentException("uid and gid must not be negative");
            if (gids.length > 16) throw new IllegalArgumentException("at most 16 supplementary groups");
            for (int g : gids) if (g < 0) throw new IllegalArgumentException("group ids must not be negative");
            this.uid = uid; this.gid = gid; this.gids = gids.clone();
        }

        byte[] credential() {
            Xdr.Writer w = new Xdr.Writer().u32(0).string("shield-launcher").u32(uid).u32(gid).u32(gids.length);
            for (int g : gids) w.u32(g);
            return w.bytes();
        }
    }

    /** Mounts {@code spec}'s directory with these credentials. Close it when done. */
    static Nfs open(String spec, Auth auth) throws IOException {
        Spec sp = Spec.parse(spec);
        byte[] cred = auth.credential();
        int mountPort = getPort(sp.host, MOUNT, 3, cred);
        if (mountPort == 0) throw new IOException(sp.host + " runs no NFSv3 mountd over TCP");
        int nfsPort = getPort(sp.host, NFS, 3, cred);
        Rpc nfs = new Rpc(sp.host, nfsPort == 0 ? NFS_PORT : nfsPort, cred);
        try {
            return new Nfs(nfs, mountRoot(sp, mountPort, nfs, cred));
        } catch (IOException | RuntimeException e) {
            nfs.close();
            throw e;
        }
    }

    /**
     * MOUNTs the path, or if the server won't mount that directory itself (it
     * is not an export), the nearest export above it and LOOKUPs the rest.
     */
    private static Node mountRoot(Spec sp, int mountPort, Rpc nfs, byte[] cred) throws IOException {
        String[] parts = sp.path.substring(1).split("/");
        StatusException first = null;
        try (Rpc mount = new Rpc(sp.host, mountPort, cred)) {
            for (int n = parts.length; n >= 0; n--) {
                StringBuilder dir = new StringBuilder();
                for (int i = 0; i < n; i++) dir.append('/').append(parts[i]);
                String path = dir.length() == 0 ? "/" : dir.toString();
                Xdr.Reader r = mount.call(MOUNT, 3, 1, new Xdr.Writer().string(path).bytes());
                int status = r.u32();
                if (status != 0) {
                    if (first == null) first = new StatusException("mount " + sp.host + ":" + path, status);
                    if (status == 13) break;   // refused, not missing: the parents will be too
                    continue;
                }
                byte[] fh = r.opaque();
                Node node = getattr(nfs, fh);
                for (int i = n; i < parts.length; i++) node = lookup(nfs, node, parts[i]);
                if (node.type != NF3DIR) throw new IOException(sp + " is not a directory");
                return node;
            }
        }
        if (first != null && first.status == 13) {
            throw new IOException(first.getMessage()
                    + " (a Linux export needs the \"insecure\" option for clients on ports above 1023)");
        }
        throw first != null ? first : new IOException("cannot mount " + sp);
    }

    /** The file or directory at {@code path}, relative to the mounted one. */
    Node stat(String path) throws IOException {
        int slash = path.lastIndexOf('/');
        return lookup(nfs, dir(slash < 0 ? "" : path.substring(0, slash)), path.substring(slash + 1));
    }

    /** Writes the whole file to {@code out}; returns its size. */
    long read(Node file, OutputStream out) throws IOException {
        if (!file.isFile()) throw new IOException("not a regular file");
        long offset = 0;
        while (true) {
            Xdr.Reader r = nfs.call(NFS, 3, 6, new Xdr.Writer()
                    .opaque(file.handle).u64(offset).u32(READ_CHUNK).bytes());
            check(r, "read");
            if (r.u32() != 0) r.skip(FATTR3);   // post_op_attr
            int count = r.u32();
            boolean eof = r.u32() != 0;
            byte[] data = r.opaque();
            out.write(data, 0, Math.min(count, data.length));
            offset += data.length;
            if (eof || data.length == 0) return offset;
        }
    }

    byte[] readAll(String path) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        read(stat(path), buf);
        return buf.toByteArray();
    }

    @Override public void close() { nfs.close(); }

    private Node dir(String path) throws IOException {
        if (path.isEmpty()) return root;
        Node d = dirs.get(path);
        if (d != null) return d;
        int slash = path.lastIndexOf('/');
        d = lookup(nfs, dir(slash < 0 ? "" : path.substring(0, slash)), path.substring(slash + 1));
        if (d.type != NF3DIR) throw new IOException(path + " is not a directory");
        dirs.put(path, d);
        return d;
    }

    private static Node lookup(Rpc nfs, Node dir, String name) throws IOException {
        if (name.isEmpty() || name.equals("..")) throw new IOException("bad path component \"" + name + "\"");
        Xdr.Reader r = nfs.call(NFS, 3, 3, new Xdr.Writer().opaque(dir.handle).string(name).bytes());
        check(r, name);
        byte[] fh = r.opaque();
        if (r.u32() == 0) return getattr(nfs, fh);   // the server left the attributes out
        return attributes(fh, r);
    }

    private static Node getattr(Rpc nfs, byte[] fh) throws IOException {
        Xdr.Reader r = nfs.call(NFS, 3, 1, new Xdr.Writer().opaque(fh).bytes());
        check(r, "getattr");
        return attributes(fh, r);
    }

    /** Reads a fattr3. */
    private static Node attributes(byte[] fh, Xdr.Reader r) throws IOException {
        int type = r.u32();
        r.skip(4 * 4);              // mode, nlink, uid, gid
        long size = r.u64();
        r.skip(8 + 8 + 8 + 8 + 8);  // used, rdev, fsid, fileid, atime
        long mtime = (r.u32() & 0xffffffffL) * 1000L + (r.u32() & 0xffffffffL) / 1_000_000;
        r.skip(8);                  // ctime
        return new Node(fh, type, size, mtime);
    }

    /** Throws on a status other than NFS3_OK. */
    private static void check(Xdr.Reader r, String what) throws IOException {
        int status = r.u32();
        if (status != 0) throw new StatusException(what, status);
    }

    private static int getPort(String host, int prog, int vers, byte[] cred) throws IOException {
        try (Rpc pm = new Rpc(host, 111, cred)) {
            return pm.call(PORTMAP, 2, 3, new Xdr.Writer().u32(prog).u32(vers).u32(6).u32(0).bytes()).u32();
        }
    }

    static String describe(int status) {
        switch (status) {
            case 1: return "not permitted";
            case 2: return "no such file or directory";
            case 5: return "I/O error";
            case 13: return "access denied";
            case 20: return "not a directory";
            case 21: return "is a directory";
            case 63: return "name too long";
            case 70: return "stale file handle";
            case 10001: return "bad file handle";
            default: return "status " + status;
        }
    }

    // ------------------------------------------------------------------ RPC

    /** One TCP connection making ONC RPC calls, one at a time. */
    static final class Rpc implements Closeable {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;
        private final byte[] cred;
        private int xid = (int) System.nanoTime();

        Rpc(String host, int port, byte[] cred) throws IOException {
            this.cred = cred;
            socket = connect(new InetSocketAddress(host, port));
            in = new DataInputStream(socket.getInputStream());
            out = new DataOutputStream(socket.getOutputStream());
        }

        /** From a reserved port if it can (a "secure" export wants one), else any. */
        private static Socket connect(InetSocketAddress to) throws IOException {
            for (int port = 1023; port >= 600; port--) {
                Socket s = new Socket();
                try {
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(port));
                } catch (BindException e) {
                    s.close();
                    if (String.valueOf(e.getMessage()).contains("in use")) continue;
                    break;   // not allowed to bind reserved ports at all
                } catch (IOException e) {
                    s.close();
                    break;
                }
                try {
                    s.connect(to, TIMEOUT_MS);
                    s.setSoTimeout(TIMEOUT_MS);
                    return s;
                } catch (BindException e) {
                    s.close();   // that port is still in TIME_WAIT to the same server
                } catch (IOException e) {
                    s.close();
                    throw e;
                }
            }
            Socket s = new Socket();
            try {
                s.connect(to, TIMEOUT_MS);
                s.setSoTimeout(TIMEOUT_MS);
                return s;
            } catch (IOException e) {
                s.close();
                throw new IOException("cannot connect to " + to.getHostString() + ":" + to.getPort()
                        + ": " + e.getMessage(), e);
            }
        }

        /** Calls a procedure; returns a reader positioned at its results. */
        Xdr.Reader call(int prog, int vers, int proc, byte[] args) throws IOException {
            int id = ++xid;
            byte[] msg = new Xdr.Writer()
                    .u32(id).u32(0).u32(2).u32(prog).u32(vers).u32(proc)
                    .u32(1).opaque(cred)    // AUTH_SYS
                    .u32(0).u32(0)          // verifier: AUTH_NONE
                    .raw(args).bytes();
            out.writeInt(0x80000000 | msg.length);
            out.write(msg);
            out.flush();

            Xdr.Reader r;
            do r = new Xdr.Reader(record()); while (r.u32() != id);   // a stale reply to an earlier call
            if (r.u32() != 1) throw new IOException("not an RPC reply");
            if (r.u32() != 0) {
                int why = r.u32();   // reject_stat: 0 version mismatch, 1 auth error
                if (why == 1) {
                    int stat = r.u32();
                    throw new IOException("NFS server rejected the credentials (auth_stat " + stat
                            + (stat == 5 ? ": AUTH_SYS too weak; the export requires Kerberos" : "")
                            + ")");
                }
                throw new IOException("RPC version mismatch");
            }
            r.skip(4);
            r.opaque();   // verifier
            int accept = r.u32();
            if (accept != 0) {
                String[] why = { "", "program unavailable", "program version mismatch",
                        "procedure unavailable", "garbage arguments", "system error" };
                throw new IOException("RPC to program " + prog + ": "
                        + (accept < why.length ? why[accept] : "accept status " + accept));
            }
            return r;
        }

        /** Reads one record-marked message, all its fragments. */
        private byte[] record() throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            while (true) {
                int mark = in.readInt();
                int len = mark & 0x7fffffff;
                if (len > 16 * 1024 * 1024) throw new IOException("RPC fragment too large: " + len);
                byte[] frag = new byte[len];
                in.readFully(frag);
                buf.write(frag);
                if (mark < 0) return buf.toByteArray();
            }
        }

        @Override public void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------ XDR

    static final class Xdr {
        private Xdr() { }

        static final class Writer {
            private final ByteArrayOutputStream b = new ByteArrayOutputStream();

            Writer u32(int v) {
                b.write(v >>> 24); b.write(v >>> 16); b.write(v >>> 8); b.write(v);
                return this;
            }
            Writer u64(long v) { return u32((int) (v >>> 32)).u32((int) v); }
            Writer opaque(byte[] v) {
                u32(v.length);
                b.write(v, 0, v.length);
                for (int i = v.length; i % 4 != 0; i++) b.write(0);
                return this;
            }
            Writer string(String s) { return opaque(s.getBytes(StandardCharsets.UTF_8)); }
            Writer raw(byte[] v) { b.write(v, 0, v.length); return this; }
            byte[] bytes() { return b.toByteArray(); }
        }

        static final class Reader {
            private final byte[] b;
            private int pos;
            Reader(byte[] b) { this.b = b; }

            int u32() throws EOFException {
                need(4);
                int v = (b[pos] & 0xff) << 24 | (b[pos + 1] & 0xff) << 16 | (b[pos + 2] & 0xff) << 8 | (b[pos + 3] & 0xff);
                pos += 4;
                return v;
            }
            long u64() throws EOFException { return (u32() & 0xffffffffL) << 32 | (u32() & 0xffffffffL); }
            byte[] opaque() throws EOFException {
                int n = u32();
                if (n < 0) throw new EOFException("bad length " + n);
                need(n);
                byte[] v = new byte[n];
                System.arraycopy(b, pos, v, 0, n);
                pos += (n + 3) & ~3;
                return v;
            }
            void skip(int n) throws EOFException { need(n); pos += n; }
            private void need(int n) throws EOFException {
                if (pos + n > b.length) throw new EOFException("short RPC reply");
            }
        }
    }
}

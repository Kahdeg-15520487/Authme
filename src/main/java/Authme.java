import arc.Core;
import arc.Events;
import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Playerc;
import mindustry.mod.Plugin;
import org.mindrot.jbcrypt.BCrypt;

import java.security.SecureRandom;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static mindustry.Vars.netServer;
import static mindustry.Vars.state;

public class Authme extends Plugin {
    Connection conn;
    RandomString randomString;

    public enum I18N {
        INFO_WELCOME_TO_SERVER,

        CMD_LOGIN_DESCRIPTION,

        CMD_REGISTER_DESCRIPTION,

        ERROR_KEY_NOT_LEGIT,

        ERROR_KEY_USED,

        ERROR_ACCOUNT_NAME_USED,

        ERROR_LOGIN_FAILED,

        INFO_LOGIN_SUCCESS
    }

    public static final Map<I18N, String> staticMap = new HashMap<>();
    public static final String countryCode = "vi";

    static {
        switch (countryCode) {
        case "vi":
            staticMap.put(I18N.INFO_WELCOME_TO_SERVER, "Bạn cần đăng nhập mới tham gia được máy chủ này.\nDùng lệnh /register <tên tài khoản> <mật khẩu mới> <key> để tạo tài khoản mới\nDùng lệnh /login <tên tài khoản> <mật khẩu> để đăng nhập");
            staticMap.put(I18N.CMD_LOGIN_DESCRIPTION, "Đăng nhập");
            staticMap.put(I18N.CMD_REGISTER_DESCRIPTION, "Đăng ký với key");
            staticMap.put(I18N.ERROR_KEY_NOT_LEGIT, "Key này không hợp lệ!");
            staticMap.put(I18N.ERROR_KEY_USED, "Key này đã được sử dụng!");
            staticMap.put(I18N.ERROR_ACCOUNT_NAME_USED, "Tên tài khoản này đã được dùng.");
            staticMap.put(I18N.ERROR_LOGIN_FAILED, "Đăng nhập thất bại! Kiểm tra lại tên tài khoản hoặc mật khẩu");
            staticMap.put(I18N.INFO_LOGIN_SUCCESS, "[green]Đăng nhập thành công!\nChào mừng trở lại, ");
            break;

        default:
            staticMap.put(I18N.INFO_WELCOME_TO_SERVER, "You must log-in to play the server. Use the /register and /login commands.");
            staticMap.put(I18N.CMD_LOGIN_DESCRIPTION, "Login to account");
            staticMap.put(I18N.CMD_REGISTER_DESCRIPTION, "Register a new account with given key");
            staticMap.put(I18N.ERROR_KEY_NOT_LEGIT, "this key is not legit!");
            staticMap.put(I18N.ERROR_KEY_USED, "this key is already used!");
            staticMap.put(I18N.ERROR_ACCOUNT_NAME_USED, "This account is already in use!");
            staticMap.put(I18N.ERROR_LOGIN_FAILED, "Login failed! Check your account id or password!");
            staticMap.put(I18N.INFO_LOGIN_SUCCESS, "[green]Login successful!\nWelcome back, ");
            break;
        }
    }

    public Authme() {
        try {
            Class.forName("org.sqlite.JDBC");
            Class.forName("org.mindrot.jbcrypt.BCrypt");
            conn = DriverManager.getConnection(
                    "jdbc:sqlite:" + Core.settings.getDataDirectory().child("player.sqlite3").absolutePath());

            String sql = "CREATE TABLE IF NOT EXISTS players(\n" + "id INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                    + "name TEXT,\n" + "uuid TEXT,\n" + "isadmin TEXT,\n" + "accountid TEXT,\n" + "accountpw TEXT\n"
                    + ");";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }

            sql = "CREATE TABLE IF NOT EXISTS keys(\n" + "id INTEGER PRIMARY KEY AUTOINCREMENT,\n" + "uuid TEXT,\n"
                    + "key TEXT);";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        } catch (ClassNotFoundException | SQLException e) {
            e.printStackTrace();
        }

        Events.on(EventType.PlayerLeave.class, e -> {
            Log.info(Groups.player.size());
            if (Groups.player.size() < 1) {
                Vars.state.serverPaused = true;
                Log.info("Vars.state.severPaused set to true");
            }
        });

        Events.on(EventType.ResetEvent.class, e -> {
            Log.info(Groups.player.size());
            if (Groups.player.size() < 1) {
                Vars.state.serverPaused = true;
                Log.info("Vars.state.severPaused set to true");
            }
        });

        Events.on(EventType.PlayerJoin.class, e -> {
            Vars.state.serverPaused = false;
            Log.info("Vars.state.severPaused set to false");
            e.player.team(nocore(e.player));
            e.player.unit().kill();
            if (login(e.player)) {
                load(e.player, getPlayerAccountName(e.player.uuid()));
            } else {
                e.player.sendMessage(staticMap.get(I18N.INFO_WELCOME_TO_SERVER));
            }
        });

        String easy = RandomString.digits + "ACEFGHJKLMNPQRUVWXYabcdefhijkprstuvwx";
        randomString = new RandomString(10, new SecureRandom(), easy);
    }

    @Override
    public void init() {
        netServer.admins.addChatFilter((player, text) -> {
            if (text.startsWith("register") || text.startsWith("login")) {
                return null;
            }
            return text;
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("genkey", "generate a registration key", arg -> {
            String newKey = createNewKey();
            if (newKey == null) {
                Log.info("key generation failed");
            } else {
                Log.info("new key: " + newKey);
            }
        });
        handler.register("keys", "list registration keys", arg -> {
            for (String k : getKeys()) {
                Log.info(k);
            }
        });
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Playerc>register("login", "<id> <password>", staticMap.get(I18N.CMD_LOGIN_DESCRIPTION), (arg, player) -> {
            String hashed = BCrypt.hashpw(arg[1], BCrypt.gensalt(11));
            if (login(player, arg[0], hashed)) {
                load(player, getPlayerAccountName(player.uuid()));
            }
        });
        handler.<Playerc>register("register", "<id> <new_password> <key>", staticMap.get(I18N.CMD_REGISTER_DESCRIPTION),
                (arg, player) -> {
                    handleRegisterNewPlayer(arg, player);
                });
        handler.<Playerc>register("reg", "<id> <new_password> <key>", staticMap.get(I18N.CMD_REGISTER_DESCRIPTION),
                (arg, player) -> {
                    handleRegisterNewPlayer(arg, player);
                });
    }

    public void handleRegisterNewPlayer(String[] arg, Playerc player) {
        try {
            Class.forName("org.mindrot.jbcrypt.BCrypt");
            String hashed = BCrypt.hashpw(arg[1], BCrypt.gensalt(11));
            if (createNewPlayer(player, player.name(), player.uuid(), player.admin(), arg[0], hashed, arg[2])) {
                load(player, getPlayerAccountName(player.uuid()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean createNewPlayer(Playerc player, String name, String uuid, boolean isAdmin, String id, String pw,
            String key) throws SQLException {
        if (!checkKey(key)) {
            player.sendMessage(staticMap.get(I18N.ERROR_KEY_NOT_LEGIT));
            return false;
        }
        if (checkKeyUsed(key)) {
            player.sendMessage(staticMap.get(I18N.ERROR_KEY_USED));
            return false;
        }
        if (!check(uuid) && !checkid(id)) {
            if (claimKey(key, uuid)) {

                PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO 'players' ('name','uuid','isadmin','accountid','accountpw') VALUES (?,?,?,?,?);");
                stmt.setString(1, name);
                stmt.setString(2, uuid);
                stmt.setBoolean(3, isAdmin);
                stmt.setString(4, id);
                stmt.setString(5, pw);
                stmt.execute();
                stmt.close();
                return true;
            }
            return false;
        } else {
            player.sendMessage(staticMap.get(I18N.ERROR_ACCOUNT_NAME_USED));
            return false;
        }
    }

    public String getNonCollissionKey() throws SQLException {
        String key = randomString.nextString();
        do {
            try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM keys WHERE key = ?;")) {
                stmt.setString(1, key);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        key = randomString.nextString();
                    } else {
                        return key;
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                return null;
            }
        } while (true);
    }

    public String createNewKey() {
        try {
            String key = getNonCollissionKey();
            PreparedStatement stmt = conn.prepareStatement("INSERT INTO 'keys' ('key','uuid') VALUES (?,NULL);");
            stmt.setString(1, key);
            stmt.execute();
            stmt.close();
            return key;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public List<String> getKeys() {
        List<String> results = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM keys;")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("key");
                    String uuid = rs.getString("uuid");
                    results.add(key + " : " + (uuid == null ? "<no player claimed>" : getPlayerDetail(uuid)));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return results;
    }

    public String getPlayerDetail(String uuid) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return "<" + rs.getString("uuid") + "|" + rs.getString("name") + "|" + rs.getString("accountid")
                            + ">";
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "<player does not exist>";
    }

    public String getPlayerAccountName(String uuid) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            stmt.setString(1, uuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("accountid");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return "<player does not exist>";
    }

    public boolean checkKey(String key) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM keys WHERE key = ?;")) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return true;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean checkKeyUsed(String key) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM keys WHERE key = ?;")) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("uuid") != null;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean claimKey(String key, String uuid) {
        if (!checkKey(key)) {
            return false;
        }
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE keys SET uuid = ? WHERE key = ?;")) {
            stmt.setString(1, uuid);
            stmt.setString(2, key);
            stmt.execute();
            stmt.close();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean check(String uuid) throws SQLException {
        PreparedStatement stmt = conn.prepareStatement("SELECT * FROM players WHERE uuid = ?;");
        stmt.setString(1, uuid);
        ResultSet rs = stmt.executeQuery();
        boolean result = rs.next();
        rs.close();
        stmt.close();
        return result;
    }

    public boolean checkid(String id) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM players WHERE accountid = ?;")) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean login(Playerc player, String id, String pw) {
        try {
            PreparedStatement stmt = conn
                    .prepareStatement("SELECT * FROM players WHERE accountid = ? AND accountpw = ?;");
            stmt.setString(1, id);
            stmt.setString(2, pw);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    if (BCrypt.checkpw(pw, rs.getString("pw")))
                        return true;
                } else {
                    player.sendMessage(staticMap.get(I18N.ERROR_LOGIN_FAILED));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean login(Playerc player) {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM players WHERE uuid = ?;")) {
            stmt.setString(1, player.uuid());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    if (rs.getString("uuid").equals(player.uuid()))
                        return true;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void load(Playerc player, String accountName) {
        player.team(state.rules.pvp ? netServer.assignTeam(player.as(), Groups.player) : Team.sharded);
        player.unit().kill();
        player.sendMessage(staticMap.get(I18N.INFO_LOGIN_SUCCESS) + accountName + "!");
    }

    public Team nocore(Playerc player) {
        int index = player.team().id + 1;
        while (index != player.team().id) {
            if (index >= Team.all.length) {
                index = 0;
            }
            if (Vars.state.teams.get(Team.all[index]).cores.isEmpty()) {
                return Team.all[index];
            }
            index++;
        }
        return player.team();
    }
}

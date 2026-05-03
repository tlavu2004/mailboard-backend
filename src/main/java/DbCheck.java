import java.sql.*;
import java.util.*;
// import org.postgresql.Driver;


public class DbCheck {
    public static void main(String[] args) throws Exception {
        Class.forName("org.postgresql.Driver");
        String url = "jdbc:postgresql://localhost:5432/mailboard";
        Properties props = new Properties();
        props.setProperty("user", "postgres");
        props.setProperty("password", "postgres");
        
        try (Connection conn = DriverManager.getConnection(url, props)) {
            System.out.println("--- EMAIL STATUS DUMP ---");
            String sql = "SELECT id, subject, status, account_id FROM emails ORDER BY id DESC LIMIT 50";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    System.out.printf("ID: %d | Subject: %s | Status: [%s] | Account: %d%n", 
                        rs.getLong("id"), 
                        rs.getString("subject"), 
                        rs.getString("status"),
                        rs.getLong("account_id"));
                }
            }
            
            System.out.println("\n--- STATUS COUNTS ---");
            sql = "SELECT status, COUNT(*) as count FROM emails GROUP BY status";
            try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    System.out.printf("Status: [%s] | Count: %d%n", rs.getString("status"), rs.getInt("count"));
                }
            }
        }
    }
}
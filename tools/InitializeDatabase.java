import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;

/** One-time initialization; only creates infi_ai_nocode and its three tables. */
class InitializeDatabase {
    public static void main(String[] args) throws Exception {
        Map<String,Object> config=new Yaml().load(Files.readString(Path.of("src/main/resources/application-local.yml")));
        Map<?,?> spring=(Map<?,?>)config.get("spring");Map<?,?> ds=(Map<?,?>)spring.get("datasource");
        String url=ds.get("url").toString();
        if(!url.contains("/infi_ai_nocode?"))throw new IllegalArgumentException("Expected new database infi_ai_nocode");
        String server=url.replace("/infi_ai_nocode?","/?");
        try(Connection c=DriverManager.getConnection(server,ds.get("username").toString(),ds.get("password").toString());Statement s=c.createStatement()){
            for(String sql:Files.readString(Path.of("sql/create_table.sql")).split(";")){if(!sql.isBlank())s.execute(sql);}
            try(ResultSet r=s.executeQuery("select count(*) from information_schema.tables where table_schema='infi_ai_nocode'")){r.next();System.out.println("infi_ai_nocode table count: "+r.getInt(1));}
        }
    }
}

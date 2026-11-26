import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.nio.file.*;
import java.net.InetSocketAddress;

public class StaticServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        Path root = Paths.get(args[1]).toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path file = root.resolve(path.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                byte[] body = "404 Not Found".getBytes();
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
                return;
            }
            String name = file.getFileName().toString().toLowerCase();
            String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".css") ? "text/css; charset=utf-8"
                    : name.endsWith(".js") || name.endsWith(".mjs") ? "text/javascript; charset=utf-8"
                    : name.endsWith(".json") ? "application/json"
                    : name.endsWith(".png") ? "image/png"
                    : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                    : name.endsWith(".svg") ? "image/svg+xml"
                    : "application/octet-stream";
            byte[] body = Files.readAllBytes(file);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });
        server.setExecutor(null);
        server.start();
        System.out.println("Serving " + root + " at http://127.0.0.1:" + port);
    }
}

package codie.mineonline;

import gg.codie.minecraft.api.SessionServer;
import gg.codie.utils.ArrayUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ProxyThread implements Runnable {
    private AtomicReference<ServerSocket> serverSocket = new AtomicReference<>();
    private Thread worker;
    private AtomicBoolean running = new AtomicBoolean(false);

    public void start() {
        worker = new Thread(this);
        worker.start();
    }

    public void stop() {
        running.set(false);
    }

    boolean isRunning() {
        return running.get();
    }

    public ProxyThread(ServerSocket serverSocket) {
        this.serverSocket.set(serverSocket);
    }

    @Override
    public void run() {
        running.set(true);
        System.setProperty("java.net.preferIPv4Stack", "true");

        Socket clientSocket = null;

        // DEBUG
//        System.out.println(serverSocket.get().getInetAddress() + ":" + serverSocket.get().getLocalPort());

        while (running.get()) {
            //Stream to put data to the browser
            PrintWriter outGoing = null;
            try {
                clientSocket = serverSocket.get().accept();
                outGoing = new PrintWriter(clientSocket.getOutputStream(), true);

                final int bufferSize = 8096;

                byte[] buffer = new byte[bufferSize];
                int bytes_read;
                LinkedList<Byte> request = new LinkedList();

                bytes_read = clientSocket.getInputStream().read(buffer, 0, bufferSize);
                for(int i = 0; i < bytes_read; i++)
                    request.add(buffer[i]);

                String requestHeaders = new String(buffer).split("\r\n\r\n")[0];

                // keep reading.
                String requestString = new String(buffer);
                for (String header : requestHeaders.split("\r\n")) {
                    if(header.contains("Content-Length")) {
                        int contentLength = Integer.parseInt(header.split(": ")[1]);
                        int headerLength = requestString.substring(0, requestString.indexOf("\r\n\r\n") + 4).length();

                        while(request.size() < contentLength + headerLength) {
                            bytes_read = clientSocket.getInputStream().read(buffer, 0, bufferSize);
                            for(int i = 0; i < bytes_read; i++)
                                request.add(buffer[i]);
                        }

                        break;
                    }
                }

                byte[] requestBytes = ArrayUtils.toPrimitives(request.toArray(new Byte[0]));
                requestString = new String(requestBytes);

                int headerSize = requestString.split("\r\n\r\n")[0].length() + 4;

                // DEBUG
//                System.out.println("Request");
                requestHeaders = requestString.split("\r\n\r\n")[0];


                // DEBUG
//                System.out.println(requestString);

                String urlString = "";
                try {
                    urlString = pullLinks(requestString).get(0);
                } catch (IndexOutOfBoundsException ex) {
                    return;
                }

                // Fix online-mode.
                if(urlString.contains("minecraft.net/game/checkserver.jsp?")) {
                    String username = null;
                    String serverId = null;
                    String ip = null;

                    String[] args = urlString.replace("http://www.minecraft.net/game/checkserver.jsp?", "").split("&");
                    for (String arg : args) {
                        String[] keyValue = arg.split("=");

                        if (keyValue[0].equals("user"))
                            username = keyValue[1];
                        else if (keyValue[0].equals("serverId"))
                            serverId = keyValue[1];
                        else if (keyValue[0].equals("ip"))
                            ip = keyValue[1];
                    }

                    if (username != null && serverId != null) {
                        boolean validJoin = SessionServer.hasJoined(
                                username,
                                serverId,
                                ip
                        );

                        if (validJoin) {
                            String responseHeaders =
                                    "HTTP/1.0 200 OKServer:Werkzeug/1.0.1 Python/3.7.0\r\n\r\n";

                            clientSocket.getOutputStream().write(responseHeaders.getBytes());
                            clientSocket.getOutputStream().write("YES".getBytes(StandardCharsets.UTF_8));
                            clientSocket.getOutputStream().flush();
                            clientSocket.getOutputStream().close();
                            continue;
                        }
                    }
                }

                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                for (String header : requestHeaders.substring(requestHeaders.indexOf("\r\n") + 2).split("\r\n")) {
                    String headerName = header.substring(0, header.indexOf(":"));
                    String headerValue = header.substring(header.indexOf(":") + 2);
                    connection.setRequestProperty(headerName, headerValue);
                }

                String methodLine = requestHeaders.substring(0, requestHeaders.indexOf("\r\n"));
                connection.setRequestMethod(methodLine.substring(0, methodLine.indexOf(" ")));

                byte[] content = new byte[0];

                try {
                    if (headerSize < requestBytes.length) {
                        content = Arrays.copyOfRange(requestBytes, headerSize, requestBytes.length);

                        connection.setUseCaches(false);
                        connection.setDoInput(true);
                        connection.setDoOutput(true);

                        DataOutputStream wr = new DataOutputStream(connection.getOutputStream());

                        wr.write(content);
                        wr.flush();
                        wr.close();
                    } else {
                        connection.setUseCaches(false);
                        connection.setDoInput(true);
                        connection.connect();
                    }


                    String responseHeader = "";
                    for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                        if (header.getKey() == null) {
                            Iterator<String> valueIterator = header.getValue().iterator();
                            responseHeader += valueIterator.next();
                            while (valueIterator.hasNext()) {
                                responseHeader += " " + valueIterator.next();
                            }
                            continue;
                        }
                        responseHeader += header.getKey() + ":";
                        Iterator<String> valueIterator = header.getValue().iterator();
                        responseHeader += valueIterator.next();
                        while (valueIterator.hasNext()) {
                            responseHeader += ", " + valueIterator.next();
                        }
                        responseHeader += "\r\n";
                    }
                    responseHeader += "\r\n";

                    String contentString = new String(content);

                    // DEBUG
//                    System.out.println("Response");
//                    System.out.print(responseHeader);
//                    System.out.println(contentString);

                    InputStream is = connection.getInputStream();

                    clientSocket.getOutputStream().write(responseHeader.getBytes());

                    buffer = new byte[bufferSize];
                    while ((bytes_read = is.read(buffer, 0, bufferSize)) != -1) {
                        for (int i = 0; i < bytes_read; i++) {
                            clientSocket.getOutputStream().write(buffer[i]);
                        }
                    }

                    clientSocket.getOutputStream().flush();
                    clientSocket.getOutputStream().close();

                } catch (Exception ex) {
                    String responseHeaders =
                            "HTTP/1.0 404 Not FoundServer:Werkzeug/1.0.1 Python/3.7.0\r\n\r\n";
                    clientSocket.getOutputStream().write(responseHeaders.getBytes());
                    clientSocket.getOutputStream().flush();
                    clientSocket.getOutputStream().close();
                    ex.printStackTrace();
                }
            } catch (FileNotFoundException ex) {
                System.err.println("Got a 404 for: " + ex.getMessage());
            } catch (IOException ex) {
                System.out.println("Something went very wrong.");
                ex.printStackTrace();
            } finally {
                outGoing.close();
                try {
                    clientSocket.close();
                } catch (Exception e) { }
            }
        }
        try {
            serverSocket.get().close();
        } catch (Exception e) { }
        running.set(false);
    }

    //Pull all links from the body for easy retrieval
    private static ArrayList<String> pullLinks(String text) {
        ArrayList<String> links = new ArrayList<String>();

        String regex = "\\(?\\b(http://|www[.])[-A-Za-z0-9+&amp;@#/%?=~_()|!:,.;]*[-A-Za-z0-9+&amp;@#/%=~_()|]";
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(text);
        while(m.find()) {
            String urlStr = m.group();
            if (urlStr.startsWith("(") && urlStr.endsWith(")"))
            {
                urlStr = urlStr.substring(1, urlStr.length() - 1);
            }
            links.add(urlStr);
        }
        return links;
    }
}
package httpProxy;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Collections.synchronizedMap;


public class httpProxy extends Thread{
    public static final int BUFFER_SIZE = 1024; // 发送的最大报文长度
    public static final int PROXY_PORT = 8888; // 代理服务器端口
    public static final int TIMEOUT = 80000; // 尝试连接的最大等待时间
    public static final int RETRY_TIMES = 5; // 尝试与目标主机连接次数
    public static final int RETRY_PAUSE = 3; // 连接失败再次尝试重连等待的时间
    public static final File CACHE_FILE = new File("cacheFile.txt");
    Request cRequest;
    public static Map<String,byte[]> cache = synchronizedMap(new HashMap<>());
    public static OutputStream cacheOutputStream;

    /**
     * 一个request被封装在一个内部类中
     * 类似c的结构体
     */
    private class Request{
        String header;
        String method;
        String requestURL;
        String host;
        String urlInCache;
        int port;


        public Request(String header, String requestURL, String method,
                       String host,String urlInCache,int port){
            this.header = header;
            this.requestURL = requestURL;
            this.method = method;
            this.host = host;
            this.urlInCache = urlInCache;
            this.port = port;
        }

        /**
         * 打印请求头的内容
         */
        public void printRequest(){
            System.out.println("header: "+header);
            System.out.println("URL: "+requestURL);
            System.out.println("method: "+method);
            System.out.println("host: "+host);
            System.out.println("port: "+port);
            System.out.println("urlInCache: "+urlInCache);
        }
    }




    InputStream cis = null, sis = null; // client/Server inputStream
    BufferedReader cbr = null, sbr = null; // client/Server bufferedReader
    OutputStream cos = null, sos = null; // client/server outputStream
    PrintWriter cpw = null, spw = null; // client/server buffered writer



    protected Socket cSocket; // client socket
    protected Socket sSocket; // server socket

    public httpProxy(Socket sk) throws FileNotFoundException {
        try {
            cacheOutputStream = new FileOutputStream(CACHE_FILE,true);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        try{
            this.cSocket = sk;
            this.cis = sk.getInputStream();//socket函数,代理服务器接受客户端请求
            this.cbr = new BufferedReader(new InputStreamReader(this.cis));
            this.cos = cSocket.getOutputStream();//socket函数,代理服务器向客户端发出响应
            this.cpw = new PrintWriter(this.cos);
            System.out.println("服务器启动...");
            if (!CACHE_FILE.exists())
                System.out.println("创建缓存文件...");
                CACHE_FILE.createNewFile();
            start();

        }catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * 开始运行代理
     */
    public static void startProxy(){
        try {
            ServerSocket serverSocket = new ServerSocket(PROXY_PORT);
            Socket newSocket = null;
            int threadCount = 0;
            while (true){
                threadCount++;
                newSocket = serverSocket.accept();
                System.out.println("启动第"+threadCount+"个线程");
                new httpProxy(newSocket);
            }
        }catch (Exception e){
            e.printStackTrace();
        }

    }

    /**
     * 一个代理线程的run执行函数
     */
    public void run() {
        try {
            cSocket.setSoTimeout(TIMEOUT);
            String header = cbr.readLine();//读取
            cRequest = parseHeader(header);
            if (cRequest == null){
                System.out.println("过滤无效头部地址的访问");
                return;
            }
            if (cRequest.method.equals("CONNECT")){
                System.out.println("过滤CONNECT连接");
                return;
            }
            System.out.println("客户发来的请求头为:");
            cRequest.printRequest();

            int leftRetryTimes = RETRY_TIMES;
            while (leftRetryTimes-- !=0 && !cRequest.host.equals("")) {
                try {
                    System.out.println("尝试与端口号为:"+ cRequest.port+
                            "的主机"+ cRequest.host+"进行连接");
                    sSocket = new Socket(cRequest.host,cRequest.port);
                    break;
                } catch (Exception e) {
                    System.out.println("与目标主机连接失败");
                }
                //wait
                Thread.sleep(RETRY_PAUSE);
            }
            if(sSocket == null){
                System.out.println("多次尝试未成功,请检查网络状况");
                return;
            }

            sSocket.setSoTimeout(TIMEOUT);
            sis = sSocket.getInputStream(); // 代理服务器作为客户端接受目标服务器响应
            sbr = new BufferedReader(new InputStreamReader(sis));
            sos = sSocket.getOutputStream(); // 代理服务器作为客户端向目标服务器发出请求
            spw = new PrintWriter(this.sos);

            String modifyTime = findInCache(cache.get(cRequest.requestURL));
            System.out.println("modifyTime: "+ modifyTime);

            String buffer = header;
            byte[] byteBuffer = "".getBytes();
            //没有缓存过
            if (modifyTime == null){
                    System.out.println("没有找到缓存,向目标发送转发请求......");
                    String clientRequest = header+"\r\n";
                    while (buffer!=null && !buffer.equals("")){
                        buffer += "\r\n";
                        clientRequest+=buffer;
                        buffer = cbr.readLine();//cbr用来缓存客户端发给代理的请求
                    }

                    //如果是CONNECT请求则先建立通道
                    if(cRequest.method.equals("CONNECT"))
                    {
                        cos.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                        cos.flush();
                    }else{//否则直接转发客户端发送来的消息
                        spw.write(clientRequest);
                        while (!buffer.equals("")){
                            buffer += "\r\n";
                            spw.write(buffer);
                            System.out.print("转发服务器请求:"+buffer);
                            buffer = cbr.readLine();//cbr用来缓存客户端发给代理的请求
                        }
                    }
                    new ProxyHandleThread(cis, sos).start();
                    spw.write("\r\n");
                    spw.flush();

                    //服务器响应消息
                    int length;
                    byte[] bytes = new byte[BUFFER_SIZE];
                    while (true){
                        try {
                            if ((length = sis.read(bytes))>0){
                                cos.write(bytes,0,length);
                                //写入到缓存
                                byteBuffer = concat(Arrays.copyOf(bytes,length),bytes);
                            }else if (length<0){
                                break;
                            }
                        }catch (Exception e){
                            e.printStackTrace();
                        }
                    }
                    cpw.write("\r\n");
                    byteBuffer = concat(byteBuffer,"\r\n".getBytes());
                    cache.put(cRequest.requestURL,byteBuffer);//将全部的buffer里面的字节数据写入对应网站的缓存中
                    cpw.close();
            }else {//缓存过,看是不是最新的
                buffer += "\r\n";
                spw.write(buffer);
                System.out.print("向目标服务器发送确认修改时间的请求:"+buffer);
                String askHost = "Host: "+cRequest.host+"\r\n";
                String askModify = "If-modified-since: " + modifyTime + "\r\n";
                spw.write(askHost+askModify);
                spw.write("\r\n");
                spw.flush();
                System.out.print(askHost+askModify);

                String timeReply = sbr.readLine();//第一行读取的是头信息
                System.out.println("收到返回信息"+timeReply);

                if (timeReply.contains("Not Modified")){  //没有修改过
                    System.out.println("没有更新,可以使用缓存中的数据");
                    outputCache(cache.get(cRequest.requestURL));
                    cpw.write("\r\n");
                    cpw.flush();
                    cpw.close();
                }else {  //修改过了,更新数据
                    System.out.println("源网站有修改,使用新的数据");
                    String content = sbr.readLine();
                    while (!content.equals("")){
                        content += "\r\n";
                        System.out.print(content);
                        cpw.write(content);
                        content = sbr.readLine();
                    }
                    cpw.write("\r\n");
                    cpw.flush();
                    cpw.close();
                }
            }

        }catch (Exception e){
            e.printStackTrace();
        }

    }

    /**
     * 解析头部得到来自client的request
     * @param header
     * @return
     */
    public Request parseHeader(String header) throws IOException {
        if (header==null||header.equals("")){
            return null;
        }
        String[] tokens = header.split(" ");
        String url = "";
        String host = "";
        String urlInCache = "";
        int port = 0;
        String method = tokens[0]; //GET or POST
        if (method.equals("CONNECT")){
            url = tokens[1];
        }
        else{
            for (int i = 0; i < tokens.length; i++) {
                if (tokens[i].startsWith("http://")) {
                    url = tokens[i];
                    break;
                }
            }
        }

        //扩展功能,钓鱼,如果是访问哈工大校园官网,自动跳转到登陆网站
        if (url.equals("http://www.hit.edu.cn/")){
            url = "http://jwts.hit.edu.cn/loginLdapQian";
            header = "GET http://jwts.hit.edu.cn/loginLdapQian HTTP/1.1";
            method = "GET";
            host = "jwts.hit.edu.cn";
            port = 80;
            urlInCache = "http://jwts.hit.edu.cn/loginLdapQian";
            return new Request(header,url,method,host,urlInCache,port);
        }
        //扩展功能,禁止访问哈工大国际合作处网站
        if (url.equals("http://www.hit.edu.cn/221/list.htm")){
            cos.write(("<html lang=\"zh-CN\"><meta http-equiv=\"content-type\" " +
                    "content=\"text/html; charset=utf-8\">禁止访问工大国际合作网站</html>").getBytes());
            cos.flush();
            url = "";
        }
        if (url.equals("http://www.people.com.cn/")){
            if (cSocket.getInetAddress().getHostAddress().equals("127.0.0.2")){
                System.out.println("禁止此用户访问人民网");
                cos.write(("<html lang=\"zh-CN\"><meta http-equiv=\"content-type\" " +
                        "content=\"text/html; charset=utf-8\">禁止此用户访问人民网</html>").getBytes());
                return null;
            }
        }
        /*
        获取host,缓存,
         */
        if(url != ""){
            if (method.equals("CONNECT")){
                tokens = url.split(":");
                if (tokens.length>1){
                    host = tokens[0];
                    port = Integer.parseInt(tokens[1]);
                }else {
                    host = url;
                    port = 80;
                }
            }else{
                Pattern hostPattern = Pattern.compile("//(.*?)/");
                Matcher hostMatcher = hostPattern.matcher(url);
                if (hostMatcher.find()){
                    host = hostMatcher.group(1);
                    //可能host里面还包含端口号
                    int portIndex = host.indexOf(":");
                    if (portIndex != -1){
                        port = Integer.parseInt(host.substring(portIndex));
                        host = host.substring(0,portIndex);
                    }else {
                        port = 80;//默认端口
                    }
                }
                int n = url.indexOf("?");
                if (n!=-1)
                    urlInCache = url.substring(0,n);
                else {
                    urlInCache = url;
                }
            }


        }
        return new Request(header,url,method,host,urlInCache,port);
    }

    /**
     * 在内存缓存中寻找对应内容的缓存
     * @param byteCache
     * @return
     */
    public String findInCache(byte[] byteCache){
        if (byteCache==null)
            return null;
        String stringCache = new String(byteCache);
        String modifyTime = null;
        String[] strings = stringCache.split("\r\n");
        for(int i = 0;i<strings.length;i++){
            if (strings[i].contains("Last-Modified:"))
            {
                System.out.println("找到了时间戳记录: " + strings[i]);
                modifyTime = strings[i].substring(16);
            }
            if (strings[i].contains("<html")||strings[i].contains("<head>"))  //已经到<html或<head>说明头部已经找完了
                return modifyTime;
        }
        return modifyTime;
    }

    /**
     * 如果已经找到了缓存，则调用这个函数输出缓存
     * @param byteCache
     */
    public void outputCache(byte[] byteCache){
        try {
            cos.write(byteCache,0,byteCache.length);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    static byte[] concat(byte[] a, byte[] b) {

        byte[] c= new byte[a.length+b.length];

        System.arraycopy(a, 0, c, 0, a.length);

        System.arraycopy(b, 0, c, a.length, b.length);

        return c;

    }

    public static void main(String[] args) {
        System.out.println("启动代理服务器......");
        httpProxy.startProxy();
    }

    static class ProxyHandleThread extends Thread {

        private InputStream input;
        private OutputStream output;

        public ProxyHandleThread(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public void run() {
            int a;
            try {
                while ((a = input.read()) != -1) {
                    output.write(a);
                }
            } catch (IOException e) {
                System.out.println("用户请求读取结束");
            }
        }
    }
}

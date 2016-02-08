package accessControl;

import http.AuthApi;
import http.AuthApi$class;
import http.AuthorizationResult;
import http.Authorized;
import http.Unauthorized;
import http.Partial;
import http.AuthorizationResult;
import scala.collection.immutable.List;
import spray.http.HttpCookie;
import types.Path;
import types.OmiTypes.OmiRequest;
import scala.collection.JavaConverters.*;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;


/**
 * Created by romanfilippov on 14/01/16.
 */
public class AuthAPIService implements AuthApi {

    private final int authServicePort = 8088;
    private final String authServiceURI = "http://localhost:" + authServicePort + "/PermissionService?ac=true";

    @Override
    public AuthorizationResult isAuthorizedForType(spray.http.HttpRequest httpRequest,
                                boolean isWrite,
                                java.lang.Iterable<Path> paths) {

        System.out.println("isAuthorizedForType EXECUTED!");

        Iterator<Path> iterator = paths.iterator();
        while (iterator.hasNext()) {
            String nextObj = iterator.next().toString();

            // the very first query to read the tree
            if (nextObj.equalsIgnoreCase("Objects")) {
                System.out.println("Root tree requested. Allowed.");
                return Authorized.instance();
            }
        }

        scala.collection.Iterator iter = httpRequest.cookies().iterator();
        if (!iter.hasNext()) {
            System.out.println("No cookies!");
            return Unauthorized.instance();
        } else {

            HttpCookie ck = null;
            while (iter.hasNext()) {
                HttpCookie nextCookie = (HttpCookie)iter.next();
                System.out.println(nextCookie.name() + ":" + nextCookie.content());

                if (nextCookie.name().equalsIgnoreCase("JSESSIONID")) {
                    ck = nextCookie;
                    break;
                }
            }

            if (ck != null) {

                String requestBody = "{\"paths\":[";
                Iterator<Path> it = paths.iterator();
                while (it.hasNext()) {
                    String nextObj = it.next().toString();

                    // the very first query to read the tree
                    if (nextObj.equalsIgnoreCase("Objects"))
                        return Authorized.instance();

                    requestBody += "\"" + nextObj + "\"";

                    if (it.hasNext())
                        requestBody += ",";
                }
                requestBody += "]}";

                System.out.println("isWrite:"+isWrite);
                System.out.println("Paths:" +requestBody);
                return sendPermissionRequest(isWrite, requestBody, ck.toString());
            } else
                return Unauthorized.instance();
        }
    }

    public AuthorizationResult isAuthorizedForRequest(spray.http.HttpRequest httpRequest,
                                   OmiRequest omiRequest) {
        return AuthApi$class.isAuthorizedForRequest(this, httpRequest, omiRequest);
    }

    public AuthorizationResult sendPermissionRequest(boolean isWrite, String body, String sessionCookie) {
        HttpURLConnection connection = null;
        try {
            //Create connection
            String writeURL = isWrite ? "true" : "false";
            String finalURL = authServiceURI + "&write=" + writeURL;
            System.out.println("Sending request. URI:" + finalURL);
            URL url = new URL(finalURL);
            connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type",
                    "application/json");

//            connection.setRequestProperty("Content-Length",
//                    Integer.toString(urlParameters.getBytes().length));
//            connection.setRequestProperty("Content-Language", "en-US");
            connection.setRequestProperty("Cookie", sessionCookie);

            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.connect();

            //Send request
            DataOutputStream wr = new DataOutputStream (
                    connection.getOutputStream());
            wr.writeBytes(body);
            wr.close();

            //Get Response
            InputStream is = connection.getInputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(is));
            StringBuilder response = new StringBuilder(); // or StringBuffer if not Java 5+
            String line;
            while((line = rd.readLine()) != null) {
                response.append(line);
            }
            rd.close();

//            System.out.println("RESPONSE:"+response.toString());

            return response.toString().equalsIgnoreCase("true") ? Authorized.instance() : Unauthorized.instance();
        } catch (Exception e) {
            e.printStackTrace();
            return Unauthorized.instance();
        } finally {
            if(connection != null) {
                connection.disconnect();
            }
        }
    }
}

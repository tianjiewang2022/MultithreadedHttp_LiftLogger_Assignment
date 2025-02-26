package server;

import org.json.JSONObject;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.annotation.*;
import java.io.IOException;
import java.io.BufferedReader;


@WebServlet(name = "SkierServlet", value = "/SkierServlet")
public class SkierServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        res.setContentType("text/plain");
        String urlPath = req.getPathInfo();
        
        if (urlPath == null || urlPath.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write("Missing parameters");
            return;
        }
        
        String[] urlParts = urlPath.split("/");
        
        if (!isUrlValid(urlParts)) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write("Invalid URL format");
        } else {
            try {
                String resortId = urlParts[1];
                String seasonId = urlParts[3];
                String dayId = urlParts[5];
                String skierId = urlParts[7];
                
                String responseMsg = String.format(
                        "It works!\n" +
                                "Resort ID: %s\n" +
                                "Season: %s\n" +
                                "Day: %s\n" +
                                "Skier ID: %s",
                        resortId, seasonId, dayId, skierId
                );
                
                res.setStatus(HttpServletResponse.SC_OK);
                res.getWriter().write(responseMsg);
                
            } catch (Exception e) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("Error processing URL parameters");
            }
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        res.setContentType("application/json");
        
        BufferedReader reader = req.getReader();
        StringBuilder jsonString = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            jsonString.append(line);
        }
        
        try {
            String json = jsonString.toString();
            
            if (json.isEmpty()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Empty JSON request body\"}");
                return;
            }
            
            // Parse the JSON string into a JSONObject (assuming you're using a library like org.json or Gson)
            JSONObject jsonObject = new JSONObject(json);
    
            // Check for missing fields in the JSON and log
            if (!jsonObject.has("time")) {
                System.out.println("'time' field missing");
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Missing 'time' field in the JSON request\"}");
                return;
            }
    
            if (!jsonObject.has("liftID")) {
                System.out.println("'liftID' field missing");
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Missing 'liftID' field in the JSON request\"}");
                return;
            }
    
            if (!jsonObject.has("skierID")) {
                System.out.println("'skierID' field missing");
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Missing 'skierID' field in the JSON request\"}");
                return;
            }
    
            if (!jsonObject.has("resortID")) {
                System.out.println("'resortID' field missing");
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Missing 'resortID' field in the JSON request\"}");
                return;
            }
    
            if (!jsonObject.has("seasonID")) {
                System.out.println("'seasonID' field missing");
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Missing 'seasonID' field in the JSON request\"}");
                return;
            }
    
            if (!jsonObject.has("dayID")) {
                System.out.println("'dayID' field missing");
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Missing 'dayID' field in the JSON request\"}");
                return;
            }
    
    
            // Validate the required fields
            if (!jsonObject.has("time") || !jsonObject.has("liftID") || !jsonObject.has("skierID") ||
                    !jsonObject.has("resortID") || !jsonObject.has("seasonID") || !jsonObject.has("dayID")) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Missing JSON data\"}");
                return;
            }
            
            // Assuming you want to send the received JSON back in the response
            res.setStatus(HttpServletResponse.SC_CREATED);
            res.getWriter().write(jsonObject.toString());
        } catch (Exception e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write("{\"error\":\"Error processing JSON\"}");
        }
    }
    
    
    private boolean isUrlValid(String[] urlPath) {
        if (urlPath.length != 8) {
            return false;
        }
        
        try {
            if (!urlPath[1].matches("\\d+")) {
                return false;
            }
            
            if (!urlPath[2].equals("seasons")) {
                return false;
            }
            
            int year = Integer.parseInt(urlPath[3]);
            if (year != 2025) {
                return false;
            }
            
            if (!urlPath[4].equals("day")) {
                return false;
            }
            
            int day = Integer.parseInt(urlPath[5]);
            if (day != 1) {
                return false;
            }
            
            if (!urlPath[6].equals("skier")) {
                return false;
            }
            
            if (!urlPath[7].matches("\\d+")) {
                return false;
            }
            
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

package server;

import org.json.JSONObject;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.annotation.*;
import java.io.IOException;
import java.io.BufferedReader;

@WebServlet(name = "SkierServlet", value = "/skiers/*")
public class SkierServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        res.setContentType("application/json");
        
        // Read the request body
        BufferedReader reader = req.getReader();
        StringBuilder jsonString = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            jsonString.append(line);
        }
        
        try {
            String json = jsonString.toString();
            
            // Check if the request body is empty
            if (json.isEmpty()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Empty JSON request body\"}");
                return;
            }
            
            // Parse the JSON string into a JSONObject
            JSONObject jsonObject = new JSONObject(json);
            
            // Validate required fields
            if (!jsonObject.has("time") || !jsonObject.has("liftID") || !jsonObject.has("skierID") ||
                    !jsonObject.has("resortID") || !jsonObject.has("seasonID") || !jsonObject.has("dayID")) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Missing required fields in the JSON request\"}");
                return;
            }
            
            // Validate field values
            int time = jsonObject.getInt("time");
            int liftID = jsonObject.getInt("liftID");
            int skierID = jsonObject.getInt("skierID");
            int resortID = jsonObject.getInt("resortID");
            int seasonID = jsonObject.getInt("seasonID");
            int dayID = jsonObject.getInt("dayID");
            
            if (time < 1 || time > 360) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid value for 'time'. Must be between 1 and 360.\"}");
                return;
            }
            if (liftID < 1 || liftID > 40) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid value for 'liftID'. Must be between 1 and 40.\"}");
                return;
            }
            if (skierID < 1 || skierID > 100000) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid value for 'skierID'. Must be between 1 and 100000.\"}");
                return;
            }
            if (resortID < 1 || resortID > 10) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid value for 'resortID'. Must be between 1 and 10.\"}");
                return;
            }
            if (seasonID != 2025) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid value for 'seasonID'. Must be 2025.\"}");
                return;
            }
            if (dayID != 1) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write("{\"error\":\"Invalid value for 'dayID'. Must be 1.\"}");
                return;
            }
            
            // If all validations pass, return a success response
            res.setStatus(HttpServletResponse.SC_CREATED);
            res.getWriter().write("{\"message\":\"Lift ride event recorded successfully.\"}");
        } catch (Exception e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write("{\"error\":\"Error processing JSON\"}");
        }
    }
}
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

public class TradierLastFridayData {

    // 🔹 Global variable: Last Friday's date
    private static final LocalDate lastFridayDate = calculateLastFriday();
    private static final String API_TOKEN = "h3zGBQqRK3OSrA5wKQYQi2I6FfUI"; // Replace with your token if needed
    private static final String OUTPUT_FILE = "qqq_" + lastFridayDate + "_minute_data.json";

    public static void main(String[] args) {
        System.out.println("Fetching minute-by-minute QQQ data for: " + lastFridayDate);

        String apiUrl = buildApiUrl();

        try {
            // Setup HTTP connection
            HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + API_TOKEN);
            conn.setRequestProperty("Accept", "application/json");

            // Read response
            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    responseBuilder.append(line);
                }
            }

            // Write to file using the global date
            try (FileWriter file = new FileWriter(OUTPUT_FILE)) {
                file.write(responseBuilder.toString());
                System.out.println("Response saved to " + OUTPUT_FILE);
            }

        } catch (IOException e) {
            System.err.println("Error fetching data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 🔹 Compute last Friday
    private static LocalDate calculateLastFriday() {
        LocalDate today = LocalDate.now();
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
    }

    // 🔹 Build API URL for minute-by-minute data using the global date
    private static String buildApiUrl() {
        // Using /v1/markets/timesales for intraday minute data
        // Specify session=regular for regular trading hours (9:30 AM - 4:00 PM ET)
        // Interval set to 1m for 1-minute data
        return String.format(
                "https://api.tradier.com/v1/markets/timesales?symbol=QQQ&interval=1m&start=%s%%2009:30:00&end=%s%%2016:00:00&session_filter=regular",
                lastFridayDate, lastFridayDate
        );
    }
}

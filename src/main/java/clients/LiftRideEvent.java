package clients;

public class LiftRideEvent {
    private int skierID;
    private int resortID;
    private int liftID;
    private int seasonID;
    private int dayID;
    private int time;
    
    
    
    public LiftRideEvent(int skierID, int resortID, int liftID, int seasonID, int dayID, int time) {
        this.skierID = skierID;
        this.resortID = resortID;
        this.liftID = liftID;
        this.seasonID = seasonID;
        this.dayID = dayID;
        this.time = time;
    }
    
    
    private int generateRandomNumber(int min, int max) {
        return min + (int) (Math.random() * (max - min + 1));
    }
    
    public int getSkierID() {
        return skierID;
    }
    
    public int getResortID() {
        return resortID;
    }
    
    public int getLiftID() {
        return liftID;
    }
    
    public int getSeasonID() {
        return seasonID;
    }
    
    public int getDayID() {
        return dayID;
    }
    
    public int getTime() {
        return time;
    }
    
    public String toJson() {
        return String.format(
                "{\"skierID\":%d,\"resortID\":%d,\"liftID\":%d,\"seasonID\":%d,\"dayID\":%d,\"time\":%d}",
                skierID, resortID, liftID, seasonID, dayID, time
        );
    }
}
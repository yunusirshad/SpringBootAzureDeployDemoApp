package com.personalscreener.model;

/** A one-shot price alert. Types: ABOVE, BELOW, PCT_UP, PCT_DOWN (percent from previous close). */
public class Alert {
    public String id;
    public String symbol;
    public String type;
    public double level;
    public String note;
    public String createdAt;
    public String triggeredAt;
    public Double triggeredPrice;
}

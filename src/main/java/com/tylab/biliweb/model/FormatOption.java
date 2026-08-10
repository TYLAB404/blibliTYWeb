package com.tylab.biliweb.model;

/** 画质选项 */
public class FormatOption {
    private int quality;
    private String description;
    private boolean available;

    public FormatOption() {}

    public FormatOption(int quality, String description, boolean available) {
        this.quality = quality;
        this.description = description;
        this.available = available;
    }

    public int getQuality() { return quality; }
    public void setQuality(int quality) { this.quality = quality; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean available) { this.available = available; }
}

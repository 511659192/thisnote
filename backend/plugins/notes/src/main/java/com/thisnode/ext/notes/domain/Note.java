package com.thisnode.ext.notes.domain;

/** 涓€鍒欑瑪璁扮殑杞婚噺瑙嗗浘锛坕d/鏍囬/姝ｆ枃/绫诲瀷/鏃堕棿鎴筹級锛涜惤鐩樺湪鍗＄墖鏂囦欢涓庣瑪璁?meta 閲屻€?*/
public class Note {

    public String id;
    public String title;
    public String contentMd;
    public String titleIndex;
    public String contentIndex;
    public String type = "note";
    public String source;
    public String status;
    public boolean archived;
    public boolean deleted;
    public Long deletedAt;
    public String dailyDate;
    public int sortOrder;
    public long createdAt;
    public long updatedAt;

    public boolean isDaily() {
        return "daily".equals(type);
    }
}

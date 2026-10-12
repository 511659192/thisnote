package com.thisnode.ext.notes.api.dto;

public record UpdateCardRequest(String noteId,
                                String cardId,
                                String title,
                                Double x,
                                Double y,
                                Double w,
                                Double h,
                                String color,
                                String status,
                                Boolean collapsed) {
}

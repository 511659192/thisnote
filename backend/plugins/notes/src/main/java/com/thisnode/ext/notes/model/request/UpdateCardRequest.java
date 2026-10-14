package com.thisnode.ext.notes.model.request;

public record UpdateCardRequest(String noteId,
                                String cardId,
                                Double x,
                                Double y,
                                Double w,
                                Double h,
                                String color,
                                String status,
                                Boolean collapsed) {
}

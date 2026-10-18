package com.thisnote.ext.favorite.model.request;

import java.util.List;

public record FavoriteBatchRequest(List<String> noteIds, boolean value) {
}

package com.campus.share.vo;
import java.util.List;
public record PopularItemsView(boolean available, List<PopularItemView> items) { }

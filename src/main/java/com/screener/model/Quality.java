package com.screener.model;

import java.util.List;

/** Shown on every row. Never removes a name. */
public record Quality(int score, String grade, List<String> notes) {}

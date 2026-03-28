package com.example.universityportal.dto;

public record CourseDto(
    long id,
    String title,
    String department,
    int credits,
    int seats
) {}


package com.example.universityportal.api;

import com.example.universityportal.dto.CourseDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CoursesController {
  private static final List<CourseDto> COURSES = List.of(
      new CourseDto(1L, "Introduction to Computer Science", "Computer Science", 4, 60),
      new CourseDto(2L, "Data Structures and Algorithms", "Computer Science", 4, 45),
      new CourseDto(3L, "Database Systems", "Information Systems", 3, 40),
      new CourseDto(4L, "Operating Systems", "Computer Science", 4, 30),
      new CourseDto(5L, "Calculus I", "Mathematics", 4, 80),
      new CourseDto(6L, "Linear Algebra", "Mathematics", 3, 55)
  );

  @GetMapping("/courses")
  public List<CourseDto> courses() {
    return COURSES;
  }
}


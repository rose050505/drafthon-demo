package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Service
class Students {
    private final JdbcTemplate db;

    Students(JdbcTemplate db) { this.db = db; }

    Map<String, Object> get(UUID student) {
        var profile = first(db.queryForList("""
            select university, major, academic_year as "academicYear", interests, availability, advisor,
                linked_course as "linkedCourse" from student_profiles where account_id = ?
            """, student));
        profile.put("documents", db.queryForList("""
            select document_type as "documentType", filename, uploaded_at as "uploadedAt"
            from student_documents where account_id = ? order by document_type
            """, student));
        return profile;
    }

    Map<String, Object> save(UUID student, StudentController.Profile input) {
        Object[] fields = {input.university().strip(), optional(input.major()), input.academicYear(),
            input.interests().strip(), input.availability().strip(), optional(input.advisor()), optional(input.linkedCourse()), student};
        int updated = db.update("""
            update student_profiles set university = ?, major = ?, academic_year = ?, interests = ?, availability = ?,
                advisor = ?, linked_course = ? where account_id = ?
            """, fields);
        if (updated == 0) {
            db.update("""
                insert into student_profiles(university, major, academic_year, interests, availability, advisor, linked_course, account_id)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, fields);
        }
        return get(student);
    }

    void saveDocument(UUID student, DocumentType type, MultipartFile file) throws IOException {
        get(student);
        if (file.isEmpty() || file.getSize() > 8 * 1024 * 1024) throw invalid("파일은 1바이트~8MB여야 합니다.");
        byte[] contents = file.getBytes();
        String media = media(contents);
        String filename = switch (media) {
            case "application/pdf" -> type.name().toLowerCase(java.util.Locale.ROOT) + ".pdf";
            case "image/png" -> type.name().toLowerCase(java.util.Locale.ROOT) + ".png";
            default -> type.name().toLowerCase(java.util.Locale.ROOT) + ".jpg";
        };
        int updated = db.update("""
            update student_documents set filename = ?, media_type = ?, contents = ?, uploaded_at = current_timestamp
            where account_id = ? and document_type = ?
            """, filename, media, contents, student, type.name());
        if (updated == 0) {
            db.update("""
                insert into student_documents(id, account_id, document_type, filename, media_type, contents) values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), student, type.name(), filename, media, contents);
        }
    }

    boolean eligible(UUID student) {
        return !db.queryForList("""
            select account_id from student_documents where account_id = ? and document_type = 'ENROLLMENT'
            """, UUID.class, student).isEmpty();
    }

    Document document(UUID student, DocumentType type) {
        return first(db.query("""
            select filename, media_type, contents from student_documents where account_id = ? and document_type = ?
            """, (row, n) -> new Document(row.getString(1), row.getString(2), row.getBytes(3)), student, type.name()));
    }

    private static String media(byte[] bytes) {
        byte[] pdf = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (prefix(bytes, pdf)) return "application/pdf";
        if (prefix(bytes, jpeg)) return "image/jpeg";
        if (prefix(bytes, png)) return "image/png";
        throw invalid("PDF, JPG, PNG 파일만 등록할 수 있습니다.");
    }

    private static boolean prefix(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && java.util.Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    record Document(String filename, String media, byte[] contents) {}
    enum DocumentType { ENROLLMENT, TRANSCRIPT, PORTFOLIO }
}

@RestController
@Tag(name = "학생 프로필·증빙", description = "학생 역할의 프로필 관리와 본인 증빙 서류 등록.")
@RequestMapping("/api/students/me")
class StudentController {
    private final Students students;

    StudentController(Students students) { this.students = students; }

    @GetMapping
    @Operation(summary = "내 학생 프로필 조회",
        description = "로그인한 학생의 학교, 전공, 관심사, 가능 시간과 등록한 증빙 목록을 조회합니다. 증빙 목록에는 유형, 파일명과 등록 시각이 포함됩니다.")
    Map<String, Object> get(@AuthenticationPrincipal Actor actor) {
        return students.get(actor.require(Role.STUDENT));
    }

    @PutMapping
    @Operation(summary = "학생 프로필 등록 또는 수정",
        description = "학교 `university`, 관심사 `interests`, 가능 시간 `availability`와 선택 항목인 전공·학년·지도교수 등을 저장합니다.")
    Map<String, Object> save(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Profile input) {
        return students.save(actor.require(Role.STUDENT), input);
    }

    @PutMapping(path = "/documents/{type}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "학생 증빙 서류 업로드",
        description = "학생 프로필을 저장한 뒤 `ENROLLMENT`, `TRANSCRIPT`, `PORTFOLIO` 중 유형을 선택합니다. PDF·JPG·PNG 파일을 8MB 이하로 `file` 항목에 올립니다. 같은 유형을 올리면 기존 파일을 갱신합니다.")
    @ResponseStatus(NO_CONTENT)
    void saveDocument(@AuthenticationPrincipal Actor actor, @PathVariable Students.DocumentType type,
        @RequestPart MultipartFile file) throws IOException {
        students.saveDocument(actor.require(Role.STUDENT), type, file);
    }

    @GetMapping("/documents/{type}")
    @Operation(summary = "내 학생 증빙 서류 다운로드",
        description = "로그인한 학생이 유형으로 자기 증빙 파일을 내려받습니다. 공고에 지원하려면 재학증명서 `ENROLLMENT`를 등록합니다.")
    ResponseEntity<byte[]> document(@AuthenticationPrincipal Actor actor, @PathVariable Students.DocumentType type) {
        var document = students.document(actor.require(Role.STUDENT), type);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(document.media()))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(document.filename()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(document.contents());
    }

    record Profile(@NotBlank @Size(max = 160) String university, @Size(max = 120) String major,
        @Min(1) @Max(8) Integer academicYear, @NotBlank @Size(max = 500) String interests,
        @NotBlank @Size(max = 1000) String availability, @Size(max = 120) String advisor, @Size(max = 150) String linkedCourse) {}
}

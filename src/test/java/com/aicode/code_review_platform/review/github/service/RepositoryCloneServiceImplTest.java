package com.aicode.code_review_platform.review.github.service;

import com.aicode.code_review_platform.review.github.RepositoryConfig;
import com.aicode.code_review_platform.review.github.exception.RepositoryCloneException;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STEP 6.2 - unit tests for the temporary repository lifecycle.
 *
 * <p>Nothing is mocked: the tests run against a real temp directory tree, because what is being
 * verified is which files survive a cleanup and which do not. The two clone tests use a local
 * repository created on the fly, so no network is involved.
 */
class RepositoryCloneServiceImplTest {

    private static final Long REVIEW_ID = 42L;

    /** Stands in for /app/temp-repositories - the shared parent that must always survive. */
    @TempDir
    Path tempRepositories;

    /** Sibling of the temp directory, used to prove cleanup will not reach outside of it. */
    @TempDir
    Path unrelated;

    private RepositoryCloneServiceImpl repositoryCloneService;

    @BeforeEach
    void setUp() {

        RepositoryConfig repositoryConfig = new RepositoryConfig();
        repositoryConfig.setTempDirectory(tempRepositories.toString());

        repositoryCloneService = new RepositoryCloneServiceImpl();
        ReflectionTestUtils.setField(repositoryCloneService, "repositoryConfig", repositoryConfig);
    }

    // ---------------------------------------------------------------------------------------
    // Unique directories
    // ---------------------------------------------------------------------------------------

    @Test
    void cloneCreatesAUniqueDirectoryForEveryReview() throws Exception {

        String sourceUrl = localRepository().toUri().toString();

        // Same review id twice - the worst case, e.g. a redelivered message overlapping the
        // original processing. The two checkouts still have to be independent.
        Path first = repositoryCloneService.cloneRepository(sourceUrl, REVIEW_ID);
        Path second = repositoryCloneService.cloneRepository(sourceUrl, REVIEW_ID);
        Path third = repositoryCloneService.cloneRepository(sourceUrl, 43L);

        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
        assertThat(first).exists().isDirectory();
        assertThat(second).exists().isDirectory();
        assertThat(third).exists().isDirectory();

        // Each one is a direct child of the configured temp directory, named after its review.
        assertThat(first.getParent()).isEqualTo(tempRepositories);
        assertThat(first.getFileName().toString()).startsWith("review-" + REVIEW_ID + "-");
        assertThat(third.getFileName().toString()).startsWith("review-43-");

        // The clone really happened - each has its own working tree and .git directory.
        assertThat(first.resolve("README.md")).exists();
        assertThat(second.resolve(".git")).exists();
    }

    @Test
    void deletingOneRepositoryLeavesConcurrentRepositoriesUntouched() throws Exception {

        Path reviewA = repositoryDirectory("review-1-aaa");
        Path reviewB = repositoryDirectory("review-2-bbb");
        Path reviewC = repositoryDirectory("review-3-ccc");

        repositoryCloneService.deleteRepository(reviewB);

        assertThat(reviewB).doesNotExist();
        assertThat(reviewA).exists();
        assertThat(reviewC).exists();
        assertThat(tempRepositories).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Recursive cleanup
    // ---------------------------------------------------------------------------------------

    @Test
    void deleteRemovesNestedDirectoriesAndFiles() throws Exception {

        Path repository = repositoryDirectory("review-" + REVIEW_ID + "-nested");
        Files.createDirectories(repository.resolve(".git/objects/pack"));
        Files.createDirectories(repository.resolve("src/main/java/com/example"));
        Files.writeString(repository.resolve(".git/HEAD"), "ref: refs/heads/main");
        Files.writeString(repository.resolve(".git/objects/pack/pack-1.idx"), "binary-ish");
        Files.writeString(repository.resolve("src/main/java/com/example/App.java"), "class App {}");
        Files.writeString(repository.resolve("README.md"), "# demo");

        repositoryCloneService.deleteRepository(repository);

        assertThat(repository).doesNotExist();

        // The parent is the shared volume mount point - it must still be there for the next review.
        assertThat(tempRepositories).exists().isDirectory();
    }

    @Test
    void deleteRemovesAClonedRepositoryIncludingItsGitDirectory() throws Exception {

        Path repository = repositoryCloneService.cloneRepository(
                localRepository().toUri().toString(),
                REVIEW_ID
        );
        assertThat(repository.resolve(".git")).exists();

        repositoryCloneService.deleteRepository(repository);

        assertThat(repository).doesNotExist();
        assertThat(tempRepositories).exists();
    }

    @Test
    void failedCloneRemovesItsOwnPartialDirectory() throws Exception {

        // An empty directory is not a git repository, so JGit fails during the transport phase -
        // after our directory has already been created.
        Path notARepository = Files.createDirectory(unrelated.resolve("not-a-repository"));

        assertThatThrownBy(() ->
                repositoryCloneService.cloneRepository(notARepository.toUri().toString(), REVIEW_ID)
        ).isInstanceOf(RepositoryCloneException.class);

        try (var entries = Files.list(tempRepositories)) {
            assertThat(entries).isEmpty();
        }
        assertThat(tempRepositories).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Safety
    // ---------------------------------------------------------------------------------------

    @Test
    void deleteIgnoresANullPath() {

        assertThatCode(() -> repositoryCloneService.deleteRepository(null)).doesNotThrowAnyException();
        assertThat(tempRepositories).exists();
    }

    @Test
    void deleteIgnoresAPathThatNoLongerExists() {

        Path alreadyGone = tempRepositories.resolve("review-99-already-deleted");

        assertThatCode(() -> repositoryCloneService.deleteRepository(alreadyGone)).doesNotThrowAnyException();
        assertThat(tempRepositories).exists();
    }

    @Test
    void deleteRefusesTheSharedTempDirectoryItself() throws Exception {

        Path survivor = repositoryDirectory("review-1-survivor");

        repositoryCloneService.deleteRepository(tempRepositories);

        assertThat(tempRepositories).exists();
        assertThat(survivor).exists();
    }

    @Test
    void deleteRefusesTheParentOfTheSharedTempDirectory() {

        // The /app case: one level above the configured directory.
        repositoryCloneService.deleteRepository(tempRepositories.getParent());

        assertThat(tempRepositories.getParent()).exists();
        assertThat(tempRepositories).exists();
    }

    @Test
    void deleteRefusesAPathOutsideTheTempDirectory() throws Exception {

        Path outside = Files.createDirectory(unrelated.resolve("review-1-elsewhere"));
        Files.writeString(outside.resolve("important.txt"), "keep me");

        repositoryCloneService.deleteRepository(outside);

        assertThat(outside).exists();
        assertThat(outside.resolve("important.txt")).exists();
    }

    @Test
    void deleteRefusesAPathThatEscapesTheTempDirectoryByTraversal() throws Exception {

        Path outside = Files.createDirectory(unrelated.resolve("review-1-traversed"));

        // Starts inside the temp directory but climbs back out through .., so it only looks like a
        // managed path until it is normalized.
        Path traversal = tempRepositories.resolve(tempRepositories.relativize(outside));
        assertThat(traversal.toString()).contains("..");

        repositoryCloneService.deleteRepository(traversal);

        assertThat(outside).exists();
    }

    @Test
    void deleteRefusesAnUnrelatedDirectoryInsideTheTempDirectory() throws Exception {

        Path notOurs = Files.createDirectory(tempRepositories.resolve("some-other-data"));
        Files.writeString(notOurs.resolve("keep.txt"), "not a clone");

        repositoryCloneService.deleteRepository(notOurs);

        assertThat(notOurs).exists();
        assertThat(notOurs.resolve("keep.txt")).exists();
    }

    @Test
    void deleteRefusesADirectoryNestedInsideARepository() throws Exception {

        Path repository = repositoryDirectory("review-1-nested-target");
        Path insideRepository = Files.createDirectories(repository.resolve("src"));

        // Only the repository root is a legitimate cleanup target; anything deeper is not.
        repositoryCloneService.deleteRepository(insideRepository);

        assertThat(insideRepository).exists();
        assertThat(repository).exists();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** A per-review directory as the clone would have created it, plus one file inside it. */
    private Path repositoryDirectory(String name) throws Exception {

        Path repository = Files.createDirectory(tempRepositories.resolve(name));
        Files.writeString(repository.resolve("file.txt"), "content");

        return repository;
    }

    /** A real local git repository with one commit, used as a clone source instead of the network. */
    private Path localRepository() throws Exception {

        Path source = Files.createDirectories(unrelated.resolve("source-repository"));

        try (Git git = Git.init().setDirectory(source.toFile()).call()) {

            Files.writeString(source.resolve("README.md"), "# demo");

            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("initial commit")
                    .setAuthor("test", "test@example.com")
                    .setCommitter("test", "test@example.com")
                    .setSign(false)
                    .call();
        }

        return source;
    }
}

#include <iostream>
#include <fstream>
#include <vector>
#include <thread>
#include <chrono>
#include <fcntl.h>
#include <unistd.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <cstdint>
#include <cassert>

// Virtiofs High Concurrency File Lock & Large File (>4GB) Offset Stress Test Harness (F-R5-007)

static const char* TEST_DIR = "/tmp/virtiofs_stress";
static const char* CONCURRENT_FILE = "/tmp/virtiofs_stress/concurrent_file.dat";
static const char* LARGE_FILE = "/tmp/virtiofs_stress/large_4gb_file.sparse";

void test_file_lock_contention() {
    std::cout << "[VIRTIOFS TEST 1] Testing file locking contention under multi-process flock..." << std::endl;
    int fd = open(CONCURRENT_FILE, O_CREAT | O_RDWR, 0666);
    if (fd < 0) {
        perror("Failed to create file");
        exit(1);
    }

    int lock_success = 0;
    int lock_busy = 0;
    const int num_threads = 10;
    std::vector<std::thread> threads;

    for (int i = 0; i < num_threads; i++) {
        threads.emplace_back([&lock_success, &lock_busy]() {
            int local_fd = open(CONCURRENT_FILE, O_RDWR);
            if (local_fd < 0) return;
            
            // Non-blocking exclusive lock attempt
            if (flock(local_fd, LOCK_EX | LOCK_NB) == 0) {
                lock_success++;
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                flock(local_fd, LOCK_UN);
            } else {
                lock_busy++;
            }
            close(local_fd);
        });
    }

    for (auto& t : threads) {
        t.join();
    }
    close(fd);

    std::cout << "Lock acquired: " << lock_success << ", Lock busy (blocked): " << lock_busy << std::endl;
    if (lock_success == 0) {
        std::cerr << "FAIL: Lock contention failed to acquire any locks!" << std::endl;
        exit(1);
    }
    std::cout << "[PASS] File lock contention test passed." << std::endl;
}

void test_large_file_offset_handling() {
    std::cout << "[VIRTIOFS TEST 2] Testing large file (>4GB offset) sparse write and seek..." << std::endl;
    int fd = open(LARGE_FILE, O_CREAT | O_RDWR | O_TRUNC, 0666);
    if (fd < 0) {
        perror("Failed to create large file");
        exit(1);
    }

    // Target offset: 5 GB = 5,368,709,120 bytes
    uint64_t offset_5gb = 5L * 1024L * 1024L * 1024L;
    off_t seek_res = lseek(fd, offset_5gb, SEEK_SET);

    if (seek_res < 0 || (uint64_t)seek_res != offset_5gb) {
        std::cerr << "FAIL: Large file lseek overflow/truncation! Expected " << offset_5gb << " got " << seek_res << std::endl;
        close(fd);
        exit(1);
    }

    const char* magic_payload = "VIRTIOFS_5GB_MAGIC_HEADER_CHECK";
    ssize_t written = write(fd, magic_payload, 31);
    if (written != 31) {
        std::cerr << "FAIL: Write at 5GB offset failed!" << std::endl;
        close(fd);
        exit(1);
    }

    struct stat st;
    if (fstat(fd, &st) == 0) {
        std::cout << "File size reported by stat: " << st.st_size << " bytes (" << (st.st_size / (1024*1024*1024)) << " GB)" << std::endl;
        if ((uint64_t)st.st_size < offset_5gb) {
            std::cerr << "FAIL: File size truncated below 5GB!" << std::endl;
            close(fd);
            exit(1);
        }
    }

    close(fd);
    unlink(LARGE_FILE);
    std::cout << "[PASS] Large file >4GB offset write & seek test passed." << std::endl;
}

int main() {
    mkdir(TEST_DIR, 0777);
    test_file_lock_contention();
    test_large_file_offset_handling();
    unlink(CONCURRENT_FILE);
    rmdir(TEST_DIR);
    return 0;
}

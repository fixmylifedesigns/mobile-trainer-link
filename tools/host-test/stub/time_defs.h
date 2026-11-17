#ifndef _TIME_DEFS_H_
#define _TIME_DEFS_H_

enum time_resolution {
    RESOLUTION_SECONDS = 0,
    RESOLUTION_MILLI_SECONDS = 1,
    RESOLUTION_MICRO_SECONDS = 2,
    RESOLUTION_NANO_SECONDS = 3,
    RESOLUTION_PICO_SECONDS = 4,
    RESOLUTION_MINUTES = 5,
    RESOLUTION_HOURS = 6
};

// Hardware specific timing related functions!
// Change this file to suit your needs!
// IMPLEMENTATION-SPECIFIC

#include <stdint.h>
#include <stdbool.h>
uint64_t fake_time_us(void);

#define MUSEC(x) (x)
#define MSEC(x) (MUSEC(x) * 1000)
#define SEC(x) (MSEC(x) * 1000)
#define MINS(x) (SEC(x) * 60)
#define HRS(x) (MINS(x) * 60)
#define NSEC(x) (MUSEC(x) / 1000)
#define PSEC(x) (NSEC(x) / 1000)

#define TIME_FUNCTION fake_time_us()

typedef uint64_t user_time_t;

#define CURR_BOARD_TIME_RESOLUTION RESOLUTION_MICRO_SECONDS

#endif /* _TIME_DEFS_H_ */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/dvb/frontend.h>
static void set_prop(struct dtv_property *p, int *n, unsigned cmd, unsigned data) {
    p[*n].cmd = cmd; p[*n].u.data = data; (*n)++;
}
int main(int argc, char **argv) {
    if (argc < 4) { printf("usage: fe_tune <MHz> <H|V> <ksym> [secs] [s|s2] [dev]\n"); return 1; }
    int mhz = atoi(argv[1]);
    int horiz = (argv[2][0] == 'H' || argv[2][0] == 'h');
    int ksym = atoi(argv[3]);
    int secs = argc > 4 ? atoi(argv[4]) : 10;
    int s2 = argc > 5 && (argv[5][0] == 's' || argv[5][0] == 'S') && argv[5][1] == '2';
    const char *dev = argc > 6 ? argv[6] : "/dev/dvb0.frontend0";
    int high = mhz >= 11700;
    int lo = high ? 10600 : 9750;
    unsigned if_khz = (unsigned)(mhz - lo) * 1000u;
    printf("freq=%d MHz pol=%c sr=%d ksym band=%s IF=%u kHz sys=%s\n", mhz, horiz ? 'H' : 'V', ksym,
           high ? "high(22k on)" : "low", if_khz, s2 ? "DVB-S2" : "DVB-S");
    int fd = open(dev, O_RDWR | O_NONBLOCK);
    if (fd < 0) { printf("open %s read/write failed: %s (errno %d)\n", dev, strerror(errno), errno); return 2; }
    if (ioctl(fd, FE_SET_TONE, SEC_TONE_OFF)) printf("set tone off: errno %d\n", errno);
    if (ioctl(fd, FE_SET_VOLTAGE, horiz ? SEC_VOLTAGE_18 : SEC_VOLTAGE_13)) printf("set voltage: errno %d\n", errno);
    usleep(20000);
    if (high && ioctl(fd, FE_SET_TONE, SEC_TONE_ON)) printf("set tone on: errno %d\n", errno);
    usleep(20000);
    struct dtv_property props[8]; int n = 0;
    set_prop(props, &n, DTV_CLEAR, 0);
    set_prop(props, &n, DTV_DELIVERY_SYSTEM, s2 ? SYS_DVBS2 : SYS_DVBS);
    set_prop(props, &n, DTV_FREQUENCY, if_khz);
    set_prop(props, &n, DTV_MODULATION, s2 ? PSK_8 : QPSK);
    set_prop(props, &n, DTV_SYMBOL_RATE, (unsigned)ksym * 1000u);
    set_prop(props, &n, DTV_INNER_FEC, FEC_AUTO);
    set_prop(props, &n, DTV_TUNE, 0);
    struct dtv_properties dp = { .num = n, .props = props };
    if (ioctl(fd, FE_SET_PROPERTY, &dp)) {
        int e = errno;
        printf("FE_SET_PROPERTY failed: errno %d, trying FE_SET_FRONTEND\n", e);
        struct dvb_frontend_parameters fp; memset(&fp, 0, sizeof fp);
        fp.frequency = if_khz; fp.inversion = INVERSION_AUTO;
        fp.u.qpsk.symbol_rate = (unsigned)ksym * 1000u; fp.u.qpsk.fec_inner = FEC_AUTO;
        if (ioctl(fd, FE_SET_FRONTEND, &fp)) { printf("FE_SET_FRONTEND failed: errno %d\n", errno); return 3; }
    }
    for (int i = 0; i < secs * 5; i++) {
        fe_status_t st = 0; unsigned short sg = 0, snr = 0; unsigned int ber = 0;
        ioctl(fd, FE_READ_STATUS, &st);
        ioctl(fd, FE_READ_SIGNAL_STRENGTH, &sg);
        ioctl(fd, FE_READ_SNR, &snr);
        ioctl(fd, FE_READ_BER, &ber);
        printf("status=0x%02x strength=%u snr=%u ber=%u\n", st, sg, snr, ber);
        fflush(stdout);
        usleep(200000);
    }
    close(fd);
    return 0;
}

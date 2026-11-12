#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/dvb/frontend.h>
int main(int argc, char **argv) {
    const char *dev = argc > 1 ? argv[1] : "/dev/dvb0.frontend0";
    int fd = open(dev, O_RDONLY | O_NONBLOCK);
    if (fd < 0) { printf("open %s failed: %s\n", dev, strerror(errno)); return 1; }
    struct dvb_frontend_info info; memset(&info, 0, sizeof info);
    if (ioctl(fd, FE_GET_INFO, &info)) printf("FE_GET_INFO errno %d\n", errno);
    else printf("name=%s type=%d freq=%u..%u kHz sr=%u..%u\n", info.name, info.type,
                info.frequency_min, info.frequency_max, info.symbol_rate_min, info.symbol_rate_max);
    struct dvb_frontend_parameters fp; memset(&fp, 0, sizeof fp);
    if (ioctl(fd, FE_GET_FRONTEND, &fp)) printf("FE_GET_FRONTEND errno %d\n", errno);
    else printf("get_frontend: freq=%u kHz sr=%u fec=%d\n", fp.frequency, fp.u.qpsk.symbol_rate, fp.u.qpsk.fec_inner);
    struct dtv_property p[4]; memset(p, 0, sizeof p);
    p[0].cmd = DTV_FREQUENCY; p[1].cmd = DTV_SYMBOL_RATE; p[2].cmd = DTV_DELIVERY_SYSTEM; p[3].cmd = DTV_INNER_FEC;
    struct dtv_properties dp = { .num = 4, .props = p };
    if (ioctl(fd, FE_GET_PROPERTY, &dp)) printf("FE_GET_PROPERTY errno %d\n", errno);
    else printf("property: freq=%u sr=%u system=%u fec=%u\n", p[0].u.data, p[1].u.data, p[2].u.data, p[3].u.data);
    fe_status_t st = 0; unsigned short sg = 0, snr = 0;
    ioctl(fd, FE_READ_STATUS, &st); ioctl(fd, FE_READ_SIGNAL_STRENGTH, &sg); ioctl(fd, FE_READ_SNR, &snr);
    printf("status=0x%02x strength=%u snr=%u\n", st, sg, snr);
    close(fd);
    return 0;
}

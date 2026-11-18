# Mesh simulator fixtures

## `juelich-2018crowdqueue-030.movements.gz`

Run 030 (corridor 5.6 m wide, 75 participants, 64.8 s) of the experiment "Crowds in front of bottlenecks from the
perspective of physics and social psychology", Wuppertal 2018, from the Jülich/Wuppertal pedestrian dynamics data
archive: <https://doi.org/10.34735/ped.2018.1> (page <https://ped.fz-juelich.de/da/doku.php?id=crowdqueue>). Authors:
Juliane Adrian, Maik Boltes, Stefan Holl, Anna Sieben, Armin Seyfried. The archive's content is licensed under
[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/); this file is a separately licensed work under that licence,
not part of the GPLv3 code beside it.

Results from the experiment: J. Adrian, A. Seyfried, A. Sieben, "Crowds in front of bottlenecks at entrances from the
perspective of physics and social psychology", J. R. Soc. Interface 17(165), 20190871 (2020),
<https://doi.org/10.1098/rsif.2019.0871>.

Changes made: the PeTrack trajectory `030_c_56_h0.txt` (sha256
`917ecaf20ae314d7f696db7473e79b7ac43bb14a7ed0166397ab3e3df30a5bc0`, from `trajectories_txt.zip`, sha256
`1918daa6c774325d8daeae31cd79da6b6cbfadeb77e8fefeffededfe979815e8`) was resampled from 25 fps to one waypoint every
0.5 s plus each pedestrian's first and last frame, shifted so every coordinate is at least 1 m from the origin, rounded
to the centimetre and written in BonnMotion's `.movements` format by

```sh
scripts/sim-trace.py 030_c_56_h0.txt juelich-2018crowdqueue-030.movements.gz
```

`TraceMobility` replays it; `FieldMeterTest` runs it through the radio end to end.

package app.getknit.knit.mesh

import app.getknit.knit.mesh.lora.AirtimeSnapshot
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardInfo
import app.getknit.knit.mesh.lora.BoardName
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.ChannelInfo
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraRadioConfig
import app.getknit.knit.mesh.lora.LoraRegion
import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.ModemPreset
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A no-op [LoraPlaneStatus] for the demo builds: a board that is bound, connected, set up for Knit and
 * hearing other radios — reported, never dialled. It is the LoRa sibling of [DemoTransport], and it exists
 * for the same reason: an emulator has no Bluetooth adapter and no Meshtastic hardware, so without it the
 * LoRa radio screen can only ever be photographed in its empty state, and the board glyph on a chat header
 * never appears at all.
 *
 * Nothing here talks to a radio. [provisionKnitChannel] reports the channel as already present rather than
 * writing anything, so a stray tap during a capture is inert.
 *
 * Only compiled into the debug variant (`src/debug`), and only wired in when `-PseedDemo=true` — see
 * `di/DemoWiring.kt`.
 */
internal class DemoLoraPlane : LoraPlaneStatus {
    override val status: StateFlow<LoraStatus> = MutableStateFlow(DEMO_STATUS)

    override suspend fun provisionKnitChannel(
        mode: ProvisionMode,
        previous: BoardSettings?,
    ): ProvisionResult = ProvisionResult.Provisioned(index = CHANNEL_INDEX, alreadyPresent = true)

    companion object {
        /** The bonded address of the demo board; also what `SettingsStore.loraDeviceAddress` is seeded to. */
        const val ADDRESS = "C4:DE:E2:14:9F:2C"

        /** Its bonded Bluetooth name — a real Heltec's shape, so the picker row reads like the hardware. */
        const val BONDED_NAME = "Heltec_V3_9f2c"

        /** The secondary slot the Knit channel sits in, as a real setup writes it (slot 0 is the primary). */
        const val CHANNEL_INDEX = 1

        /** Node number → the `!` id on the screen, the `Knit 9f2c` mesh name, and the board-name check. */
        private val NODE_NUM = 0xDA3C9F2Cu

        /** The board's firmware. Shown on the screen, and what decides the identity a setup writes it. */
        private const val FIRMWARE = "2.5.20.4c97351"

        private val RADIO =
            LoraRadioConfig(
                usePreset = true,
                modemPreset = ModemPreset.LONG_FAST,
                region = LoraRegion.OTHER,
                hopLimit = 3,
                overrideDutyCycle = false,
            )

        /**
         * A board that has been set up for Knit: the stock primary left alone on its own frequency (so the
         * "your primary has been renamed" warning stays off), the Knit channel in slot 1, and the board
         * already carrying the name a setup gives it (so the rename prompt stays off too).
         */
        private val READY =
            LinkState.Ready(
                board =
                    BoardInfo(
                        myNodeNum = NODE_NUM,
                        pioEnv = "heltec-v3",
                        firmwareVersion = FIRMWARE,
                        owner = BoardName.forNode(NODE_NUM, FIRMWARE),
                    ),
                channels =
                    listOf(
                        ChannelInfo(index = 0, name = ModemPreset.LONG_FAST.defaultChannelName, role = 1),
                        ChannelInfo(index = CHANNEL_INDEX, name = KnitChannel.NAME, role = 2),
                    ),
                mtu = 255,
                radio = RADIO,
            )

        /**
         * The airtime ledger, spent to a bit over a third of the window: enough for the settings row to
         * report a real percentage, and comfortably under `LoraStatusRepository.AIRTIME_SPENT_SHARE`, so
         * the "this DM will wait for air" notice — a *degraded* state — never paints itself across a
         * marketing screenshot.
         */
        private val AIRTIME =
            AirtimeSnapshot(
                preset = ModemPreset.LONG_FAST,
                region = LoraRegion.OTHER,
                known = true,
                usedMs = 62_000L,
                allowanceMs = 180_000L,
                publicCeilingMs = 90_000L,
            )

        private val DEMO_STATUS =
            LoraStatus(
                state = READY,
                boardName = BONDED_NAME,
                boardAddress = ADDRESS,
                boardNodeNum = NODE_NUM,
                lastSnr = 6.25f,
                lastRssi = -94,
                queueFree = 14,
                // Two people reached over the air, three radios heard on the channel: "heard" and
                // "boards heard" differ on purpose, since the screen exists partly to separate them.
                heard = 2,
                boardsHeard = 3,
                battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false),
                airtime = AIRTIME,
            )
    }
}

/**
 * A [BoardDirectory] of bonded devices for the demo picker: the demo board plus two ordinary bonded
 * peripherals. The extras are the point — [app.getknit.knit.mesh.lora.BoardFilter] hides them, so the
 * picker shows its "N hidden / show all" affordance rather than a one-row list that never explains why
 * the user's headphones are missing.
 */
internal class DemoBoardDirectory : BoardDirectory {
    override fun bonded(): List<BoardRef> =
        listOf(
            BoardRef(address = DemoLoraPlane.ADDRESS, name = DemoLoraPlane.BONDED_NAME, meshtastic = true),
            BoardRef(address = "3C:71:BF:04:A1:88", name = "WH-1000XM5", meshtastic = false),
            BoardRef(address = "F8:5C:7D:2B:60:11", name = "Pixel Watch", meshtastic = false),
        )
}

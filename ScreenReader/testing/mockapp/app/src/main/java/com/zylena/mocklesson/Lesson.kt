package com.zylena.mocklesson

/** One day of the practice lesson: a heading and paragraphs of different lengths, one taller than the screen. */
data class Day(val date: String, val title: String, val paras: List<String>)

// Shared sentences that fill the paragraphs out to the length each one needs.
private val POOL = listOf(
    "Mula pa noong unang panahon, nais ng Diyos na makipag-usap sa Kanyang mga anak.",
    "Ginamit Niya ang mga propeta, mga panaginip, at mga pangitain upang ipahayag ang Kanyang kalooban.",
    "Sa bawat henerasyon, may mga taong tapat na nakinig at sumunod sa Kanyang tinig.",
    "Ang kalikasan mismo ay nagsasalita tungkol sa Kanyang kapangyarihan at karunungan.",
    "Kapag pinagmamasdan natin ang mga bituin sa gabi, naaalala natin kung gaano kalawak ang Kanyang nilikha.",
    "Ngunit hindi sapat ang kalikasan upang lubos nating makilala ang Kanyang pag-ibig.",
    "Kaya ibinigay Niya ang Kasulatan bilang malinaw na gabay sa ating paglalakbay.",
    "Sa pag-aaral ng Salita, natututuhan natin ang Kanyang mga pangako at ang Kanyang plano para sa atin.",
    "Hindi ito aklat na basta binabasa lamang; ito ay sulat ng isang Amang nagmamahal.",
    "Ang mga sumulat nito ay mga karaniwang tao na pinili at ginabayan ng Banal na Espiritu.",
    "Iba-iba ang kanilang pinagmulan: may mangingisda, may pastol, may hari, at may manggagamot.",
    "Gayunman, iisa ang mensaheng dala nila mula simula hanggang wakas.",
    "Ito ang dahilan kung bakit maaari tayong magtiwala sa bawat salitang nakasulat dito.",
    "Habang binabasa natin ito araw-araw, unti-unting nababago ang ating puso at isipan.",
    "Itanong natin sa ating sarili kung paano natin tinatanggap ang mga mensaheng ito ngayon.",
)

private var next = 0

/** A paragraph that starts with its own sentence (so the test can tell them apart), about this many characters long. */
private fun para(opening: String, length: Int): String {
    val b = StringBuilder(opening)
    while (b.length < length) {
        b.append(' ').append(POOL[next % POOL.size])
        next++
    }
    return b.toString()
}

val DAYS = listOf(
    Day(
        "Oktubre 18 - 24", "Pahayag at Inspirasyon",
        listOf(
            para("“Ang Apocalipsis ni Jesu-Cristo, na ibinigay ng Diyos sa kanya upang ipakita sa kanyang mga alipin.” (Apocalipsis 1:1)", 260),
            para("Ang apostol na si Juan ay kabilang sa Labindalawang Apostol na pinili ni Cristo.", 520),
            para("Sa Patmos, natanggap ni Juan ang espesyal na mga propetikong pangitain mula sa langit.", 680),
            para("Ang liksyon sa linggong ito ay tiyakang sinusuri ang maraming tanong tungkol sa pahayag.", 360),
            para("Pag-aralan ang liksyon sa linggong ito bilang paghahanda sa Sabbath, Oktubre 25.", 240),
        )
    ),
    Day(
        "Linggo, Oktubre 19", "Pangkalahatan at Espesyal na Pahayag",
        listOf(
            para("Basahin ang Hebreo 1:1, 2. Bakit masyadong interesado ang Diyos na makipag-ugnayan sa atin?", 210),
            para("Ano ang sasabihin ng Manlilikha ng Sansinukob sa atin? Bakit Siya mag-aabala pa?", 610),
            para("Ang pangkalahatang pahayag ng Diyos, na makikita sa kalikasan, ay para sa lahat ng tao.", 720),
            para("Kahanga-hanga ring isipin ang nagpapanumbalik na kapangyarihan na nakikita natin sa paligid.", 1650),
            para("Bagaman napakaganda ng natural na mundo, ito ay hindi lamang ang tanging aklat ng Diyos.", 420),
            para("Ang Kasulatan ay kinasihang tala ng mga mensahe ng Diyos para sa sangkatauhan.", 300),
            para("Bagaman malinaw na ipinapakita ng kalikasan ang kapangyarihan ng Diyos, may hangganan ito.", 520),
        )
    ),
    Day(
        "Lunes, Oktubre 20", "Ang Salita ay Kinasihan",
        listOf(
            para("Basahin ang 2 Timoteo 3:16, 17. Ano ang ibig sabihin ng salitang kinasihan?", 230),
            para("Ang mga propeta ay hindi nagsalita ayon sa sarili nilang kagustuhan o karunungan.", 560),
            para("Sa loob ng mahigit isang libong taon, mga apatnapung manunulat ang ginamit ng Diyos.", 640),
            para("Isipin ang isang sulat na isinulat ng maraming kamay ngunit iisa ang may-akda.", 380),
            para("Paano mababago ng pagtitiwalang ito ang paraan ng iyong pagbabasa ng Bibliya ngayong linggo?", 260),
        )
    ),
)

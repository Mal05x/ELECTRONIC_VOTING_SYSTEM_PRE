-- ============================================================
-- V2: Nigerian States, LGAs & Polling Units
-- All 36 states + FCT and all 774 LGAs seeded.
-- Polling units are created via POST /api/admin/polling-units.
-- ============================================================

CREATE TABLE states (
    id   SERIAL       PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    code VARCHAR(5)   NOT NULL UNIQUE
);

CREATE TABLE lgas (
    id       SERIAL       PRIMARY KEY,
    name     VARCHAR(150) NOT NULL,
    state_id INTEGER      NOT NULL REFERENCES states(id),
    UNIQUE (name, state_id)
);

CREATE TABLE polling_units (
    id       BIGSERIAL    PRIMARY KEY,
    name     VARCHAR(200) NOT NULL,
    code     VARCHAR(50)  UNIQUE,
    lga_id   INTEGER      NOT NULL REFERENCES lgas(id),
    capacity INTEGER      NOT NULL DEFAULT 500
);

-- Add geo columns to voter_registry and ballot_box
ALTER TABLE voter_registry ADD COLUMN polling_unit_id BIGINT REFERENCES polling_units(id);
ALTER TABLE ballot_box
    ADD COLUMN state_id        INTEGER,
    ADD COLUMN lga_id          INTEGER,
    ADD COLUMN polling_unit_id BIGINT;

-- Add FK constraints for sessions (already has columns from V1)
ALTER TABLE voting_sessions
    ADD CONSTRAINT fk_session_pu FOREIGN KEY (polling_unit_id) REFERENCES polling_units(id);

CREATE INDEX idx_voter_pu     ON voter_registry(polling_unit_id);
CREATE INDEX idx_ballot_state ON ballot_box(election_id, state_id);
CREATE INDEX idx_ballot_lga   ON ballot_box(election_id, lga_id);
CREATE INDEX idx_ballot_pu    ON ballot_box(election_id, polling_unit_id);
CREATE INDEX idx_lga_state    ON lgas(state_id);
CREATE INDEX idx_pu_lga       ON polling_units(lga_id);

-- ── Seed all 36 states + FCT ─────────────────────────────────
INSERT INTO states (name, code) VALUES
('Abia','AB'),('Adamawa','AD'),('Akwa Ibom','AK'),('Anambra','AN'),
('Bauchi','BA'),('Bayelsa','BY'),('Benue','BE'),('Borno','BO'),
('Cross River','CR'),('Delta','DE'),('Ebonyi','EB'),('Edo','ED'),
('Ekiti','EK'),('Enugu','EN'),('FCT','FC'),('Gombe','GO'),
('Imo','IM'),('Jigawa','JI'),('Kaduna','KD'),('Kano','KN'),
('Katsina','KT'),('Kebbi','KB'),('Kogi','KO'),('Kwara','KW'),
('Lagos','LA'),('Nasarawa','NA'),('Niger','NI'),('Ogun','OG'),
('Ondo','ON'),('Osun','OS'),('Oyo','OY'),('Plateau','PL'),
('Rivers','RI'),('Sokoto','SO'),('Taraba','TA'),('Yobe','YO'),
('Zamfara','ZA');

-- ── Seed all 774 LGAs ────────────────────────────────────────
-- ABIA (17)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Aba North'),('Aba South'),('Arochukwu'),('Bende'),('Ikwuano'),
('Isiala Ngwa North'),('Isiala Ngwa South'),('Isuikwuato'),('Obi Ngwa'),
('Ohafia'),('Osisioma'),('Ugwunagbo'),('Ukwa East'),('Ukwa West'),
('Umuahia North'),('Umuahia South'),('Umu Nneochi')
) AS t(l) WHERE states.code='AB';

-- ADAMAWA (21)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Demsa'),('Fufure'),('Ganye'),('Gayuk'),('Gombi'),('Grie'),
('Hong'),('Jada'),('Lamurde'),('Madagali'),('Maiha'),('Mayo-Belwa'),
('Michika'),('Mubi North'),('Mubi South'),('Numan'),('Shelleng'),
('Song'),('Toungo'),('Yola North'),('Yola South')
) AS t(l) WHERE states.code='AD';

-- AKWA IBOM (31)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Abak'),('Eastern Obolo'),('Eket'),('Esit Eket'),('Essien Udim'),
('Etim Ekpo'),('Etinan'),('Ibeno'),('Ibesikpo Asutan'),('Ibiono-Ibom'),
('Ika'),('Ikono'),('Ikot Abasi'),('Ikot Ekpene'),('Ini'),('Itu'),
('Mbo'),('Mkpat-Enin'),('Nsit-Atai'),('Nsit-Ibom'),('Nsit-Ubium'),
('Obot Akara'),('Okobo'),('Onna'),('Oron'),('Oruk Anam'),
('Udung-Uko'),('Ukanafun'),('Uruan'),('Urue-Offong/Oruko'),('Uyo')
) AS t(l) WHERE states.code='AK';

-- ANAMBRA (21)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Aguata'),('Anambra East'),('Anambra West'),('Anaocha'),('Awka North'),
('Awka South'),('Ayamelum'),('Dunukofia'),('Ekwusigo'),('Idemili North'),
('Idemili South'),('Ihiala'),('Njikoka'),('Nnewi North'),('Nnewi South'),
('Ogbaru'),('Onitsha North'),('Onitsha South'),('Orumba North'),
('Orumba South'),('Oyi')
) AS t(l) WHERE states.code='AN';

-- BAUCHI (20)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Alkaleri'),('Bauchi'),('Bogoro'),('Damban'),('Darazo'),('Dass'),
('Gamawa'),('Ganjuwa'),('Giade'),('Itas/Gadau'),('Jama''are'),
('Katagum'),('Kirfi'),('Misau'),('Ningi'),('Shira'),
('Tafawa Balewa'),('Toro'),('Warji'),('Zaki')
) AS t(l) WHERE states.code='BA';

-- BAYELSA (8)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Brass'),('Ekeremor'),('Kolokuma/Opokuma'),('Nembe'),
('Ogbia'),('Sagbama'),('Southern Ijaw'),('Yenagoa')
) AS t(l) WHERE states.code='BY';

-- BENUE (23)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Ado'),('Agatu'),('Apa'),('Buruku'),('Gboko'),('Guma'),
('Gwer East'),('Gwer West'),('Katsina-Ala'),('Konshisha'),('Kwande'),
('Logo'),('Makurdi'),('Obi'),('Ogbadibo'),('Ohimini'),('Oju'),
('Okpokwu'),('Otukpo'),('Tarka'),('Ukum'),('Ushongo'),('Vandeikya')
) AS t(l) WHERE states.code='BE';

-- BORNO (27)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Abadam'),('Askira/Uba'),('Bama'),('Bayo'),('Biu'),('Chibok'),
('Damboa'),('Dikwa'),('Gubio'),('Guzamala'),('Gwoza'),('Hawul'),
('Jere'),('Kaga'),('Kala/Balge'),('Konduga'),('Kukawa'),
('Kwaya Kusar'),('Mafa'),('Magumeri'),('Maiduguri'),('Marte'),
('Mobbar'),('Monguno'),('Ngala'),('Nganzai'),('Shani')
) AS t(l) WHERE states.code='BO';

-- CROSS RIVER (18)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Abi'),('Akamkpa'),('Akpabuyo'),('Bakassi'),('Bekwarra'),('Biase'),
('Boki'),('Calabar Municipal'),('Calabar South'),('Etung'),('Ikom'),
('Obanliku'),('Obubra'),('Obudu'),('Odukpani'),('Ogoja'),
('Yakuur'),('Yala')
) AS t(l) WHERE states.code='CR';

-- DELTA (25)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Aniocha North'),('Aniocha South'),('Bomadi'),('Burutu'),
('Ethiope East'),('Ethiope West'),('Ika North East'),('Ika South'),
('Isoko North'),('Isoko South'),('Ndokwa East'),('Ndokwa West'),
('Okpe'),('Oshimili North'),('Oshimili South'),('Patani'),('Sapele'),
('Udu'),('Ughelli North'),('Ughelli South'),('Ukwuani'),('Uvwie'),
('Warri North'),('Warri South'),('Warri South West')
) AS t(l) WHERE states.code='DE';

-- EBONYI (13)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Abakaliki'),('Afikpo North'),('Afikpo South'),('Ebonyi'),
('Ezza North'),('Ezza South'),('Ikwo'),('Ishielu'),('Ivo'),
('Izzi'),('Ohaozara'),('Ohaukwu'),('Onicha')
) AS t(l) WHERE states.code='EB';

-- EDO (18)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Akoko-Edo'),('Egor'),('Esan Central'),('Esan North-East'),
('Esan South-East'),('Esan West'),('Etsako Central'),('Etsako East'),
('Etsako West'),('Igueben'),('Ikpoba-Okha'),('Oredo'),('Orhionmwon'),
('Ovia North-East'),('Ovia South-West'),('Owan East'),
('Owan West'),('Uhunmwonde')
) AS t(l) WHERE states.code='ED';

-- EKITI (16)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Ado Ekiti'),('Efon'),('Ekiti East'),('Ekiti South-West'),
('Ekiti West'),('Emure'),('Gbonyin'),('Ido/Osi'),('Ijero'),
('Ikere'),('Ikole'),('Ilejemeje'),('Irepodun/Ifelodun'),
('Ise/Orun'),('Moba'),('Oye')
) AS t(l) WHERE states.code='EK';

-- ENUGU (17)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Aninri'),('Awgu'),('Enugu East'),('Enugu North'),('Enugu South'),
('Ezeagu'),('Igbo Etiti'),('Igbo Eze North'),('Igbo Eze South'),
('Isi Uzo'),('Nkanu East'),('Nkanu West'),('Nsukka'),('Oji River'),
('Udenu'),('Udi'),('Uzo-Uwani')
) AS t(l) WHERE states.code='EN';

-- FCT (6 Area Councils)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Abaji'),('Abuja Municipal'),('Bwari'),('Gwagwalada'),
('Kuje'),('Kwali')
) AS t(l) WHERE states.code='FC';

-- GOMBE (11)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Akko'),('Balanga'),('Billiri'),('Dukku'),('Funakaye'),('Gombe'),
('Kaltungo'),('Kwami'),('Nafada'),('Shongom'),('Yamaltu/Deba')
) AS t(l) WHERE states.code='GO';

-- IMO (27)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Aboh Mbaise'),('Ahiazu Mbaise'),('Ehime Mbano'),('Ezinihitte'),
('Ideato North'),('Ideato South'),('Ihitte/Uboma'),('Ikeduru'),
('Isiala Mbano'),('Isu'),('Mbaitoli'),('Ngor Okpala'),('Njaba'),
('Nkwerre'),('Nwangele'),('Obowo'),('Oguta'),('Ohaji/Egbema'),
('Okigwe'),('Onuimo'),('Orlu'),('Orsu'),('Oru East'),
('Oru West'),('Owerri Municipal'),('Owerri North'),('Owerri West')
) AS t(l) WHERE states.code='IM';

-- JIGAWA (27)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Auyo'),('Babura'),('Biriniwa'),('Birnin Kudu'),('Buji'),('Dutse'),
('Gagarawa'),('Garki'),('Gumel'),('Guri'),('Gwaram'),('Gwiwa'),
('Hadejia'),('Jahun'),('Kafin Hausa'),('Kaugama'),('Kazaure'),
('Kiri Kasama'),('Kiyawa'),('Maigatari'),('Malam Madori'),('Miga'),
('Ringim'),('Roni'),('Sule Tankarkar'),('Taura'),('Yankwashi')
) AS t(l) WHERE states.code='JI';

-- KADUNA (23)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Birnin Gwari'),('Chikun'),('Giwa'),('Igabi'),('Ikara'),
('Jaba'),('Jema''a'),('Kachia'),('Kaduna North'),('Kaduna South'),
('Kagarko'),('Kajuru'),('Kaura'),('Kauru'),('Kubau'),
('Kudan'),('Lere'),('Makarfi'),('Sabon Gari'),('Sanga'),
('Soba'),('Zangon Kataf'),('Zaria')
) AS t(l) WHERE states.code='KD';

-- KANO (44)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Ajingi'),('Albasu'),('Bagwai'),('Bebeji'),('Bichi'),('Bunkure'),
('Dala'),('Dambatta'),('Dawakin Kudu'),('Dawakin Tofa'),('Doguwa'),
('Fagge'),('Gabasawa'),('Garko'),('Garun Mallam'),('Gaya'),
('Gezawa'),('Gwale'),('Gwarzo'),('Kabo'),('Kano Municipal'),
('Karaye'),('Kibiya'),('Kiru'),('Kumbotso'),('Kunchi'),('Kura'),
('Madobi'),('Makoda'),('Minjibir'),('Nasarawa Kano'),('Rano'),
('Rimin Gado'),('Rogo'),('Shanono'),('Sumaila'),('Takai'),
('Tarauni'),('Tofa'),('Tsanyawa'),('Tudun Wada'),
('Ungogo'),('Warawa'),('Wudil')
) AS t(l) WHERE states.code='KN';

-- KATSINA (34)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Bakori'),('Batagarawa'),('Batsari'),('Baure'),('Bindawa'),
('Charanchi'),('Dan Musa'),('Dandume'),('Danja'),('Daura'),
('Dutsi'),('Dutsin-Ma'),('Faskari'),('Funtua'),('Ingawa'),
('Jibia'),('Kafur'),('Kaita'),('Kankara'),('Kankia'),
('Katsina'),('Kurfi'),('Kusada'),('Mai''adua'),('Malumfashi'),
('Mani'),('Mashi'),('Matazu'),('Musawa'),('Rimi'),
('Sabuwa'),('Safana'),('Sandamu'),('Zango')
) AS t(l) WHERE states.code='KT';

-- KEBBI (21)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Aleiro'),('Arewa Dandi'),('Argungu'),('Augie'),('Bagudo'),
('Birnin Kebbi'),('Bunza'),('Dandi'),('Fakai'),('Gwandu'),
('Jega'),('Kalgo'),('Koko/Besse'),('Maiyama'),('Ngaski'),
('Sakaba'),('Shanga'),('Suru'),('Wasagu/Danko'),('Yauri'),('Zuru')
) AS t(l) WHERE states.code='KB';

-- KOGI (21)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Adavi'),('Ajaokuta'),('Ankpa'),('Bassa'),('Dekina'),('Ibaji'),
('Idah'),('Igalamela-Odolu'),('Ijumu'),('Kabba/Bunu'),('Kogi'),
('Lokoja'),('Mopa-Muro'),('Ofu'),('Ogori/Magongo'),('Okehi'),
('Okene'),('Olamaboro'),('Omala'),('Yagba East'),('Yagba West')
) AS t(l) WHERE states.code='KO';

-- KWARA (16)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Asa'),('Baruten'),('Edu'),('Ekiti Kwara'),('Ifelodun'),
('Ilorin East'),('Ilorin South'),('Ilorin West'),('Irepodun'),
('Isin'),('Kaiama'),('Moro'),('Offa'),('Oke Ero'),
('Oyun'),('Pategi')
) AS t(l) WHERE states.code='KW';

-- LAGOS (20)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Agege'),('Ajeromi-Ifelodun'),('Alimosho'),('Amuwo-Odofin'),
('Apapa'),('Badagry'),('Epe'),('Eti-Osa'),('Ibeju-Lekki'),
('Ifako-Ijaiye'),('Ikeja'),('Ikorodu'),('Kosofe'),
('Lagos Island'),('Lagos Mainland'),('Mushin'),('Ojo'),
('Oshodi-Isolo'),('Shomolu'),('Surulere')
) AS t(l) WHERE states.code='LA';

-- NASARAWA (13)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Akwanga'),('Awe'),('Doma'),('Karu'),('Keana'),('Keffi'),
('Kokona'),('Lafia'),('Nasarawa'),('Nasarawa Egon'),
('Obi'),('Toto'),('Wamba')
) AS t(l) WHERE states.code='NA';

-- NIGER (25)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Agaie'),('Agwara'),('Bida'),('Borgu'),('Bosso'),('Chanchaga'),
('Edati'),('Gbako'),('Gurara'),('Katcha'),('Kontagora'),('Lapai'),
('Lavun'),('Magama'),('Mariga'),('Mashegu'),('Mokwa'),('Moya'),
('Paikoro'),('Rafi'),('Rijau'),('Shiroro'),('Suleja'),
('Tafa'),('Wushishi')
) AS t(l) WHERE states.code='NI';

-- OGUN (20)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Abeokuta North'),('Abeokuta South'),('Ado-Odo/Ota'),
('Egbado North'),('Egbado South'),('Ewekoro'),('Ifo'),
('Ijebu East'),('Ijebu North'),('Ijebu North East'),
('Ijebu Ode'),('Ikenne'),('Imeko Afon'),('Ipokia'),
('Obafemi Owode'),('Odeda'),('Odogbolu'),
('Ogun Waterside'),('Remo North'),('Shagamu')
) AS t(l) WHERE states.code='OG';

-- ONDO (18)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Akoko North-East'),('Akoko North-West'),('Akoko South-East'),
('Akoko South-West'),('Akure North'),('Akure South'),('Ese Odo'),
('Idanre'),('Ifedore'),('Ilaje'),('Ile Oluji/Okeigbo'),
('Irele'),('Odigbo'),('Okitipupa'),('Ondo East'),('Ondo West'),
('Ose'),('Owo')
) AS t(l) WHERE states.code='ON';

-- OSUN (30)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Aiyedade'),('Aiyedire'),('Atakumosa East'),('Atakumosa West'),
('Boluwaduro'),('Boripe'),('Ede North'),('Ede South'),
('Egbedore'),('Ejigbo'),('Ife Central'),('Ife East'),
('Ife North'),('Ife South'),('Ifedayo'),('Ifelodun Osun'),
('Ila'),('Ilesa East'),('Ilesa West'),('Irepodun Osun'),
('Irewole'),('Isokan'),('Iwo'),('Obokun'),('Odo Otin'),
('Ola Oluwa'),('Olorunda'),('Oriade'),('Orolu'),('Osogbo')
) AS t(l) WHERE states.code='OS';

-- OYO (33)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Afijio'),('Akinyele'),('Atiba'),('Atisbo'),('Egbeda'),
('Ibadan North'),('Ibadan North-East'),('Ibadan North-West'),
('Ibadan South-East'),('Ibadan South-West'),('Ibarapa Central'),
('Ibarapa East'),('Ibarapa North'),('Ido'),('Irepo'),
('Iseyin'),('Itesiwaju'),('Iwajowa'),('Kajola'),('Lagelu'),
('Ogbomosho North'),('Ogbomosho South'),('Ogo Oluwa'),
('Olorunsogo'),('Oluyole'),('Ona Ara'),('Orelope'),
('Ori Ire'),('Oyo East'),('Oyo West'),('Saki East'),
('Saki West'),('Surulere Oyo')
) AS t(l) WHERE states.code='OY';

-- PLATEAU (17)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Barkin Ladi'),('Bassa'),('Bokkos'),('Jos East'),('Jos North'),
('Jos South'),('Kanam'),('Kanke'),('Langtang North'),
('Langtang South'),('Mangu'),('Mikang'),('Pankshin'),
('Qua''an Pan'),('Riyom'),('Shendam'),('Wase')
) AS t(l) WHERE states.code='PL';

-- RIVERS (23)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Abua/Odual'),('Ahoada East'),('Ahoada West'),('Akuku-Toru'),
('Andoni'),('Asari-Toru'),('Bonny'),('Degema'),('Eleme'),
('Emohua'),('Etche'),('Gokana'),('Ikwerre'),('Khana'),
('Obio/Akpor'),('Ogba/Egbema/Ndoni'),('Ogu/Bolo'),('Okrika'),
('Omuma'),('Opobo/Nkoro'),('Oyigbo'),('Port Harcourt'),('Tai')
) AS t(l) WHERE states.code='RI';

-- SOKOTO (23)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Binji'),('Bodinga'),('Dange Shuni'),('Gada'),('Goronyo'),
('Gudu'),('Gwadabawa'),('Illela'),('Isa'),('Kebbe'),('Kware'),
('Rabah'),('Sabon Birni'),('Shagari'),('Silame'),
('Sokoto North'),('Sokoto South'),('Tambuwal'),('Tangaza'),
('Tureta'),('Wamako'),('Wurno'),('Yabo')
) AS t(l) WHERE states.code='SO';

-- TARABA (16)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Ardo Kola'),('Bali'),('Donga'),('Gashaka'),('Gassol'),
('Ibi'),('Jalingo'),('Karim Lamido'),('Kumi'),('Lau'),
('Sardauna'),('Takum'),('Ussa'),('Wukari'),('Yorro'),('Zing')
) AS t(l) WHERE states.code='TA';

-- YOBE (17)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Bade'),('Bursari'),('Damaturu'),('Fika'),('Fune'),('Geidam'),
('Gujba'),('Gulani'),('Jakusko'),('Karasuwa'),('Machina'),
('Nangere'),('Nguru'),('Potiskum'),('Tarmuwa'),
('Yunusari'),('Yusufari')
) AS t(l) WHERE states.code='YO';

-- ZAMFARA (14)
INSERT INTO lgas (name,state_id) SELECT l,id FROM states,(VALUES
('Anka'),('Bakura'),('Birnin Magaji/Kiyaw'),('Bukkuyum'),
('Bungudu'),('Gummi'),('Gusau'),('Kaura Namoda'),('Maradun'),
('Maru'),('Shinkafi'),('Talata Mafara'),('Tsafe'),('Zurmi')
) AS t(l) WHERE states.code='ZA';

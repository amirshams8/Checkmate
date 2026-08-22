package com.checkmate.core

/**
 * Official full syllabus for JEE / NEET / CUET (NTA) and SSC CGL (Staff Selection Commission).
 * Structure: Exam → Subject → Chapter → List<Topic>.
 *
 * NEET block is transcribed directly from the NTA/NMC NEET(UG)-2026 notified syllabus
 * (Public Notice 08.01.2026, Annexure-I). Each "Chapter" here is one official NTA "UNIT"
 * (e.g. "UNIT 2: KINEMATICS" -> chapter "Kinematics") — this replaces an earlier version
 * where a handful of broad groupings ("Mechanics", "Physical Chemistry") sat one level too
 * coarse and stood in as Chapter, with each real NTA unit collapsed into a single flat Topic
 * under it. That meant "Kinematics" existed only as one topic under "Mechanics", with no way
 * to distinguish a student's grasp of, say, Projectile Motion from Relative Velocity. Topics
 * are now the actual granular sub-concepts NTA lists within each unit. JEE/CUET/SSC CGL blocks
 * are unchanged and still use the coarser grouping — same real-syllabus treatment applies to
 * them if/when needed.
 * SSC CGL block reflects the 2026 notification syllabus — Tier 1 (Quantitative Aptitude,
 * General Intelligence & Reasoning, English Language, General Awareness) plus the
 * Tier 2 Paper 1 additions (Computer Knowledge, Statistics for JSO posts).
 */
object ExamSyllabus {

    val data: Map<String, Map<String, Map<String, List<String>>>> = mapOf(

        "NEET" to mapOf(
            "Physics" to mapOf(
                "Physics And Measurement" to listOf("Units and Systems of Measurement", "SI Units", "Fundamental and Derived Units", "Least Count", "Significant Figures", "Errors in Measurement", "Dimensions of Physical Quantities", "Dimensional Analysis and Applications"),
                "Kinematics" to listOf("Frame of Reference", "Motion in a Straight Line", "Position-Time Graph", "Speed and Velocity", "Uniform and Non-Uniform Motion", "Average Speed and Instantaneous Velocity", "Uniformly Accelerated Motion", "Velocity-Time Graph", "Scalars and Vectors", "Vector Addition and Subtraction", "Scalar and Vector Products", "Unit Vector", "Resolution of a Vector", "Relative Velocity", "Motion in a Plane", "Projectile Motion", "Uniform Circular Motion"),
                "Laws Of Motion" to listOf("Force and Inertia", "Newton's First Law of Motion", "Momentum", "Newton's Second Law of Motion", "Impulse", "Newton's Third Law of Motion", "Conservation of Linear Momentum", "Equilibrium of Concurrent Forces", "Static and Kinetic Friction", "Laws of Friction", "Rolling Friction", "Centripetal Force", "Vehicle on a Level Circular Road", "Vehicle on a Banked Road"),
                "Work Energy And Power" to listOf("Work Done by Constant and Variable Force", "Kinetic Energy", "Potential Energy", "Work-Energy Theorem", "Power", "Potential Energy of a Spring", "Conservation of Mechanical Energy", "Conservative and Non-Conservative Forces", "Motion in a Vertical Circle", "Elastic Collisions", "Inelastic Collisions"),
                "Rotational Motion" to listOf("Centre of Mass", "Torque", "Angular Momentum", "Conservation of Angular Momentum", "Moment of Inertia", "Radius of Gyration", "Parallel and Perpendicular Axes Theorems", "Equilibrium of Rigid Bodies", "Rigid Body Rotation", "Comparison of Linear and Rotational Motion"),
                "Gravitation" to listOf("Universal Law of Gravitation", "Acceleration Due to Gravity", "Variation of g with Altitude and Depth", "Kepler's Laws of Planetary Motion", "Gravitational Potential Energy", "Gravitational Potential", "Escape Velocity", "Orbital Velocity", "Time Period and Energy of Satellite"),
                "Properties Of Solids And Liquids" to listOf("Elastic Behaviour", "Stress-Strain Relationship", "Hooke's Law", "Young's Modulus", "Bulk Modulus", "Modulus of Rigidity", "Pressure Due to a Fluid Column", "Pascal's Law", "Viscosity", "Stokes' Law", "Terminal Velocity", "Streamline and Turbulent Flow", "Bernoulli's Principle", "Surface Tension", "Angle of Contact", "Capillary Rise", "Heat and Temperature", "Thermal Expansion", "Specific Heat Capacity", "Calorimetry", "Change of State and Latent Heat", "Heat Transfer — Conduction Convection Radiation"),
                "Thermodynamics" to listOf("Thermal Equilibrium", "Zeroth Law of Thermodynamics", "Heat Work and Internal Energy", "First Law of Thermodynamics", "Isothermal and Adiabatic Processes", "Second Law of Thermodynamics", "Reversible and Irreversible Processes"),
                "Kinetic Theory Of Gases" to listOf("Equation of State of a Perfect Gas", "Work Done on Compressing a Gas", "Kinetic Theory Assumptions", "Concept of Pressure", "Kinetic Interpretation of Temperature", "RMS Speed of Gas Molecules", "Degrees of Freedom", "Law of Equipartition of Energy", "Mean Free Path", "Avogadro's Number"),
                "Oscillations And Waves" to listOf("Periodic Motion", "Displacement as a Function of Time", "Simple Harmonic Motion", "Phase in SHM", "Oscillations of a Spring", "Energy in SHM", "Simple Pendulum", "Wave Motion", "Longitudinal and Transverse Waves", "Speed of Travelling Wave", "Superposition of Waves", "Reflection of Waves", "Standing Waves in Strings and Organ Pipes", "Beats"),
                "Electrostatics" to listOf("Electric Charges and Conservation of Charge", "Coulomb's Law", "Superposition Principle", "Electric Field Due to a Point Charge", "Electric Field Lines", "Electric Dipole", "Torque on a Dipole", "Electric Flux", "Gauss's Law and Applications", "Electric Potential", "Equipotential Surfaces", "Electrical Potential Energy", "Conductors and Insulators", "Dielectrics and Polarization", "Capacitors and Capacitance", "Combination of Capacitors", "Energy Stored in a Capacitor"),
                "Current Electricity" to listOf("Electric Current", "Drift Velocity and Mobility", "Ohm's Law", "V-I Characteristics", "Electrical Energy and Power", "Resistivity and Conductivity", "Series and Parallel Combination of Resistors", "Temperature Dependence of Resistance", "Internal Resistance EMF and Terminal Voltage", "Combination of Cells", "Kirchhoff's Laws", "Wheatstone Bridge", "Metre Bridge"),
                "Magnetic Effects Of Current And Magnetism" to listOf("Biot-Savart Law", "Ampere's Law", "Force on a Moving Charge in Magnetic Field", "Force on a Current-Carrying Conductor", "Force Between Two Parallel Currents", "Torque on a Current Loop", "Moving Coil Galvanometer", "Current Loop as Magnetic Dipole", "Bar Magnet as Equivalent Solenoid", "Magnetic Field Lines", "Magnetic Field Due to a Bar Magnet", "Torque on a Magnetic Dipole", "Para Dia and Ferromagnetic Substances"),
                "Electromagnetic Induction And Alternating Currents" to listOf("Faraday's Law", "Induced EMF and Current", "Lenz's Law", "Eddy Currents", "Self and Mutual Inductance", "Alternating Current", "Peak and RMS Values", "Reactance and Impedance", "LCR Series Circuit and Resonance", "Power in AC Circuits", "AC Generator", "Transformer"),
                "Electromagnetic Waves" to listOf("Displacement Current", "Characteristics of Electromagnetic Waves", "Transverse Nature of EM Waves", "Electromagnetic Spectrum", "Applications of EM Waves"),
                "Optics" to listOf("Reflection of Light", "Spherical Mirrors and Mirror Formula", "Refraction of Light", "Thin Lens Formula and Lens Maker Formula", "Total Internal Reflection", "Magnification", "Power of a Lens", "Combination of Thin Lenses", "Refraction Through a Prism", "Microscope and Telescope", "Wavefront and Huygens' Principle", "Interference and Young's Double Slit Experiment", "Diffraction Due to a Single Slit", "Polarization"),
                "Dual Nature Of Matter And Radiation" to listOf("Photoelectric Effect", "Einstein's Photoelectric Equation", "Particle Nature of Light", "Matter Waves and de Broglie Relation"),
                "Atoms And Nuclei" to listOf("Alpha-Particle Scattering Experiment", "Rutherford's Model of Atom", "Bohr Model and Energy Levels", "Hydrogen Spectrum", "Composition and Size of Nucleus", "Mass-Energy Relation", "Binding Energy Per Nucleon", "Nuclear Fission and Fusion"),
                "Electronic Devices" to listOf("Semiconductors", "Semiconductor Diode I-V Characteristics", "Diode as a Rectifier", "LED Photodiode Solar Cell Zener Diode", "Zener Diode as Voltage Regulator", "Logic Gates"),
                "Experimental Skills" to listOf("Vernier Calipers", "Screw Gauge", "Simple Pendulum Experiment", "Metre Scale Moments Experiment", "Young's Modulus Experiment", "Surface Tension by Capillary Rise", "Coefficient of Viscosity Experiment", "Speed of Sound Using Resonance Tube", "Specific Heat Capacity by Method of Mixtures", "Resistivity Using Metre Bridge", "Resistance Using Ohm's Law", "Galvanometer Figure of Merit", "Focal Length of Mirrors and Lens", "Angle of Deviation vs Angle of Incidence Prism", "Refractive Index Using Travelling Microscope", "P-N Junction Diode Characteristics", "Zener Diode Characteristics", "Identification of Circuit Components")
            ),
            "Chemistry" to mapOf(
                "Some Basic Concepts In Chemistry" to listOf("Dalton's Atomic Theory", "Atom Molecule Element and Compound", "Laws of Chemical Combination", "Atomic and Molecular Masses", "Mole Concept and Molar Mass", "Percentage Composition", "Empirical and Molecular Formulae", "Chemical Equations and Stoichiometry"),
                "Atomic Structure" to listOf("Electromagnetic Radiation and Photoelectric Effect", "Hydrogen Spectrum", "Bohr Model of Hydrogen Atom", "Limitations of Bohr's Model", "de Broglie Relationship", "Heisenberg Uncertainty Principle", "Quantum Mechanical Model of Atom", "Atomic Orbitals as Wave Functions", "Quantum Numbers", "Shapes of s p d Orbitals", "Electron Spin", "Aufbau Principle", "Pauli's Exclusion Principle", "Hund's Rule", "Electronic Configuration of Elements"),
                "Chemical Bonding And Molecular Structure" to listOf("Kossel-Lewis Approach", "Ionic Bonding and Lattice Enthalpy", "Electronegativity and Fajan's Rule", "Dipole Moment", "VSEPR Theory", "Valence Bond Theory", "Hybridization", "Resonance", "Molecular Orbital Theory", "Bonding and Antibonding Orbitals", "Sigma and Pi Bonds", "Bond Order Length and Energy", "Metallic Bonding", "Hydrogen Bonding"),
                "Chemical Thermodynamics" to listOf("System and Surroundings", "State Functions and Types of Processes", "First Law of Thermodynamics", "Internal Energy and Enthalpy", "Heat Capacity", "Hess's Law", "Enthalpies of Bond Dissociation Combustion Formation", "Second Law of Thermodynamics", "Spontaneity of Processes", "Gibbs Energy and Equilibrium Constant"),
                "Solutions" to listOf("Methods of Expressing Concentration", "Vapour Pressure and Raoult's Law", "Ideal and Non-Ideal Solutions", "Colligative Properties", "Relative Lowering of Vapour Pressure", "Depression of Freezing Point", "Elevation of Boiling Point", "Osmotic Pressure", "Determination of Molecular Mass", "Van't Hoff Factor"),
                "Equilibrium" to listOf("Dynamic Equilibrium", "Equilibria Involving Physical Processes", "Henry's Law", "Law of Chemical Equilibrium", "Equilibrium Constants Kp and Kc", "Le Chatelier's Principle", "Weak and Strong Electrolytes", "Acids and Bases — Arrhenius Bronsted-Lowry Lewis", "Ionization Constants", "pH Scale", "Common Ion Effect", "Hydrolysis of Salts", "Solubility Product", "Buffer Solutions"),
                "Redox Reactions And Electrochemistry" to listOf("Oxidation and Reduction", "Oxidation Number", "Balancing Redox Reactions", "Electrolytic and Metallic Conduction", "Molar Conductivity", "Kohlrausch's Law", "Electrolytic and Galvanic Cells", "Electrode Potential", "Nernst Equation", "Cell Potential and Gibbs Energy", "Dry Cell and Lead Accumulator", "Fuel Cells"),
                "Chemical Kinetics" to listOf("Rate of a Chemical Reaction", "Factors Affecting Rate of Reaction", "Order and Molecularity", "Rate Law and Rate Constant", "Zero and First Order Reactions", "Half-Life of Reactions", "Effect of Temperature on Rate", "Arrhenius Theory and Activation Energy", "Collision Theory"),
                "Classification Of Elements And Periodicity In Properties" to listOf("Modern Periodic Law", "s p d f Block Elements", "Periodic Trends — Atomic and Ionic Radii", "Ionization Enthalpy", "Electron Gain Enthalpy", "Valence and Oxidation States", "Chemical Reactivity Trends"),
                "P-Block Elements" to listOf("Group 13 to Group 18 Elements", "Electronic Configuration Trends", "Physical and Chemical Property Trends", "Anomalous Behaviour of First Element in Each Group"),
                "d And f Block Elements" to listOf("Transition Elements — Electronic Configuration and Occurrence", "Ionization Enthalpy and Oxidation States of Transition Elements", "Atomic Radii Colour and Catalytic Behaviour", "Magnetic Properties and Complex Formation", "Interstitial Compounds and Alloy Formation", "Preparation and Properties of K2Cr2O7 and KMnO4", "Lanthanoids and Lanthanoid Contraction", "Actinoids"),
                "Co-Ordination Compounds" to listOf("Werner's Theory", "Ligands Coordination Number and Denticity", "Chelation", "IUPAC Nomenclature of Coordination Compounds", "Isomerism in Coordination Compounds", "Valence Bond Approach", "Crystal Field Theory", "Colour and Magnetic Properties", "Importance of Coordination Compounds"),
                "Purification And Characterisation Of Organic Compounds" to listOf("Crystallization Sublimation Distillation", "Differential Extraction and Chromatography", "Qualitative Analysis — Detection of N S P Halogens", "Quantitative Analysis — Estimation of C H N Halogens S P", "Empirical and Molecular Formulae Calculation"),
                "Some Basic Principles Of Organic Chemistry" to listOf("Tetravalency of Carbon", "Hybridization and Shapes of Molecules", "Classification by Functional Groups", "Homologous Series", "Structural and Stereoisomerism", "IUPAC Nomenclature", "Homolytic and Heterolytic Fission", "Free Radicals Carbocations and Carbanions", "Electrophiles and Nucleophiles", "Inductive Effect", "Electromeric Effect", "Resonance and Hyperconjugation", "Substitution Addition Elimination Rearrangement Reactions"),
                "Hydrocarbons" to listOf("Classification Isomerism and Nomenclature of Hydrocarbons", "Alkanes — Conformations and Halogenation Mechanism", "Alkenes — Geometrical Isomerism", "Electrophilic Addition Mechanism", "Markovnikov's Rule and Peroxide Effect", "Ozonolysis and Polymerization", "Alkynes — Acidic Character and Addition Reactions", "Aromatic Hydrocarbons — Benzene Structure and Aromaticity", "Electrophilic Substitution — Halogenation and Nitration", "Friedel-Crafts Alkylation and Acylation"),
                "Organic Compounds Containing Halogens" to listOf("Preparation Properties and Reactions of Haloalkanes and Haloarenes", "Nature of C-X Bond", "Mechanisms of Substitution Reactions", "Uses and Environmental Effects of Chloroform Iodoform Freons DDT"),
                "Organic Compounds Containing Oxygen" to listOf("Alcohols — Primary Secondary Tertiary and Dehydration Mechanism", "Phenols — Acidic Nature and Electrophilic Substitution", "Reimer-Tiemann Reaction", "Ethers — Structure", "Aldehydes and Ketones — Nucleophilic Addition", "Grignard Reagent", "Aldol Condensation and Cannizzaro Reaction", "Haloform Reaction", "Carboxylic Acids — Acidic Strength"),
                "Organic Compounds Containing Nitrogen" to listOf("Amines — Nomenclature Classification and Basic Character", "Primary Secondary Tertiary Amines", "Diazonium Salts"),
                "Biomolecules" to listOf("Carbohydrates — Classification Aldoses and Ketoses", "Monosaccharides and Oligosaccharides", "Proteins — Amino Acids and Peptide Bond", "Protein Structure — Primary Secondary Tertiary Quaternary", "Denaturation of Proteins and Enzymes", "Vitamins — Classification and Functions", "Nucleic Acids — DNA and RNA Structure and Function"),
                "Principles Related To Practical Chemistry" to listOf("Detection of Extra Elements in Organic Compounds", "Detection of Functional Groups", "Preparation of Inorganic Compounds — Mohr's Salt Potash Alum", "Preparation of Organic Compounds — Acetanilide Iodoform", "Titrimetric Exercises — Acid Base and Redox Titrations", "Qualitative Salt Analysis — Cations", "Qualitative Salt Analysis — Anions", "Enthalpy of Solution and Neutralization Experiments", "Preparation of Lyophilic and Lyophobic Sols", "Kinetic Study of Iodide-Peroxide Reaction")
            ),
            "Biology" to mapOf(
                "Diversity In Living World" to listOf("What is Living — Biodiversity and Need for Classification", "Taxonomy and Systematics", "Concept of Species and Taxonomical Hierarchy", "Binomial Nomenclature", "Five Kingdom Classification", "Monera Protista and Fungi", "Lichens Viruses and Viroids", "Classification of Plants — Algae Bryophytes Pteridophytes Gymnosperms", "Classification of Animals — Nonchordates and Chordates"),
                "Structural Organisation In Animals And Plants" to listOf("Morphology and Modifications of Flowering Plants", "Plant Tissues", "Anatomy of Root Stem Leaf Inflorescence Flower Fruit Seed", "Plant Families — Malvaceae Cruciferae Leguminoceae Compositae Graminae", "Animal Tissues", "Morphology and Anatomy of an Insect — Digestive Circulatory Respiratory Nervous Reproductive Systems"),
                "Cell Structure And Function" to listOf("Cell Theory", "Prokaryotic and Eukaryotic Cell Structure", "Cell Envelope Cell Membrane Cell Wall", "Cell Organelles — Endoplasmic Reticulum Golgi Bodies Lysosomes Vacuoles", "Mitochondria Ribosomes Plastids Microbodies", "Cytoskeleton Cilia Flagella Centrioles", "Nucleus — Nuclear Membrane Chromatin Nucleolus", "Biomolecules — Proteins Carbohydrates Lipids Nucleic Acids", "Enzymes — Types Properties and Action", "Cell Cycle Mitosis and Meiosis"),
                "Plant Physiology" to listOf("Photosynthesis as Autotrophic Nutrition", "Site and Pigments of Photosynthesis", "Photochemical and Biosynthetic Phases", "Cyclic and Non-Cyclic Photophosphorylation", "Chemiosmotic Hypothesis", "Photorespiration and C3 C4 Pathways", "Factors Affecting Photosynthesis", "Cellular Respiration — Glycolysis Fermentation TCA Cycle Electron Transport", "Respiratory Quotient", "Seed Germination and Plant Growth Phases", "Differentiation Dedifferentiation Redifferentiation", "Plant Growth Regulators — Auxin Gibberellin Cytokinin Ethylene ABA"),
                "Human Physiology" to listOf("Respiratory System and Mechanism of Breathing", "Exchange and Transport of Gases", "Respiratory Disorders — Asthma Emphysema", "Composition of Blood and Blood Groups", "Coagulation of Blood", "Composition and Function of Lymph", "Human Heart and Blood Vessels", "Cardiac Cycle and Cardiac Output", "Double Circulation and Regulation of Cardiac Activity", "Circulatory Disorders — Hypertension Coronary Artery Disease", "Modes of Excretion — Ammonotelism Ureotelism Uricotelism", "Human Excretory System Structure", "Urine Formation and Osmoregulation", "Regulation of Kidney Function", "Excretory Disorders — Uraemia Renal Failure Dialysis", "Types of Movement and Skeletal Muscle Contraction", "Skeletal System and Joints", "Muscular and Skeletal Disorders", "Neuron and Nervous System", "Generation and Conduction of Nerve Impulse", "Endocrine Glands and Hormones", "Human Endocrine System", "Hormone Action and Related Disorders"),
                "Reproduction" to listOf("Flower Structure and Gametophyte Development", "Pollination Types and Agencies", "Outbreeding Devices and Pollen-Pistil Interaction", "Double Fertilization", "Development of Endosperm Embryo Seed and Fruit", "Apomixis Parthenocarpy Polyembryony", "Male and Female Reproductive Systems", "Gametogenesis — Spermatogenesis and Oogenesis", "Menstrual Cycle", "Fertilisation and Embryo Development to Blastocyst", "Pregnancy Placenta and Parturition", "Lactation", "Need for Reproductive Health", "Birth Control and Contraception", "Medical Termination of Pregnancy and Amniocentesis", "Infertility and Assisted Reproductive Technologies"),
                "Genetics And Evolution" to listOf("Mendelian Inheritance", "Incomplete Dominance Co-Dominance Multiple Alleles", "Pleiotropy and Polygenic Inheritance", "Chromosome Theory of Inheritance", "Sex Determination", "Linkage and Crossing Over", "Sex-Linked Inheritance — Haemophilia Colour Blindness", "Mendelian Disorders — Thalassemia", "Chromosomal Disorders — Down's Turner's Klinefelter's Syndrome", "DNA as Genetic Material", "Structure of DNA and RNA", "DNA Packaging and Replication", "Central Dogma Transcription and Translation", "Genetic Code", "Gene Expression and Regulation — Lac Operon", "Genome and Human Genome Project", "DNA Fingerprinting", "Origin of Life", "Evidence for Biological Evolution", "Darwin's Contribution and Modern Synthetic Theory", "Mutation Recombination and Natural Selection", "Gene Flow and Genetic Drift", "Hardy-Weinberg Principle", "Adaptive Radiation and Human Evolution"),
                "Biology And Human Welfare" to listOf("Pathogens and Parasites Causing Human Diseases", "Basic Concepts of Immunology and Vaccines", "Cancer HIV and AIDS", "Adolescence Drug and Alcohol Abuse", "Microbes in Household Food Processing", "Microbes in Industrial Production and Sewage Treatment", "Microbes as Biocontrol Agents and Biofertilizers"),
                "Biotechnology And Its Applications" to listOf("Principles of Biotechnology", "Genetic Engineering — Recombinant DNA Technology", "Human Insulin and Vaccine Production", "Gene Therapy", "Genetically Modified Organisms — Bt Crops", "Transgenic Animals", "Biosafety Issues — Biopiracy and Patents"),
                "Ecology And Environment" to listOf("Population Interactions — Mutualism Competition Predation Parasitism", "Population Attributes — Growth Birth Rate Death Rate", "Ecosystem Patterns and Components", "Productivity and Decomposition", "Energy Flow", "Ecological Pyramids", "Concept and Patterns of Biodiversity", "Importance and Loss of Biodiversity", "Biodiversity Conservation — Hotspots Red Data Book Biosphere Reserves")
            )
        ),

        "JEE" to mapOf(
            "Mathematics" to mapOf(
                "Algebra" to listOf(
                    "Complex Numbers", "Quadratic Equations", "Sequences and Series",
                    "Permutations and Combinations", "Binomial Theorem",
                    "Matrices and Determinants", "Mathematical Induction"
                ),
                "Trigonometry" to listOf(
                    "Trigonometric Ratios", "Trigonometric Equations",
                    "Inverse Trigonometry", "Properties of Triangles"
                ),
                "Calculus" to listOf(
                    "Limits and Continuity", "Differentiation", "Applications of Derivatives",
                    "Integration", "Definite Integrals", "Differential Equations",
                    "Area Under Curves"
                ),
                "Coordinate Geometry" to listOf(
                    "Straight Lines", "Circles", "Parabola", "Ellipse", "Hyperbola"
                ),
                "Vectors and 3D" to listOf(
                    "Vectors", "3D Geometry", "Planes and Lines in 3D"
                ),
                "Probability and Statistics" to listOf(
                    "Probability", "Bayes Theorem", "Statistics", "Random Variables"
                )
            ),
            "Physics" to mapOf(
                "Mechanics" to listOf(
                    "Kinematics", "Newton's Laws", "Work Energy Theorem",
                    "Rotational Dynamics", "Gravitation", "Simple Harmonic Motion",
                    "Waves and Sound", "Fluid Mechanics"
                ),
                "Thermodynamics" to listOf(
                    "Laws of Thermodynamics", "Kinetic Theory", "Calorimetry"
                ),
                "Electromagnetism" to listOf(
                    "Electrostatics", "Current Electricity", "Magnetism",
                    "Electromagnetic Induction", "AC Circuits", "EM Waves"
                ),
                "Optics and Modern Physics" to listOf(
                    "Ray Optics", "Wave Optics", "Photoelectric Effect",
                    "Atomic Models", "Nuclear Physics", "Semiconductors"
                )
            ),
            "Chemistry" to mapOf(
                "Physical Chemistry" to listOf(
                    "Atomic Structure", "Chemical Bonding", "Thermodynamics",
                    "Chemical Equilibrium", "Electrochemistry", "Chemical Kinetics",
                    "Solutions", "Solid State", "Surface Chemistry"
                ),
                "Organic Chemistry" to listOf(
                    "IUPAC Nomenclature", "Reaction Mechanisms",
                    "Hydrocarbons", "Functional Group Chemistry",
                    "Biomolecules", "Polymers", "Practical Organic Chemistry"
                ),
                "Inorganic Chemistry" to listOf(
                    "Periodic Table Trends", "Chemical Bonding Advanced",
                    "s p d f Block Elements", "Coordination Chemistry",
                    "Analytical Chemistry", "Metallurgy"
                )
            )
        ),

        "CUET" to mapOf(
            "Biology" to mapOf(
                "Class 11 Biology" to listOf(
                    "Diversity of Living World", "Structural Organisation in Animals and Plants",
                    "Cell Structure and Function", "Plant Physiology", "Human Physiology"
                ),
                "Class 12 Biology" to listOf(
                    "Reproduction", "Genetics and Evolution",
                    "Biology and Human Welfare", "Biotechnology", "Ecology"
                )
            ),
            "Chemistry" to mapOf(
                "Class 11 Chemistry" to listOf(
                    "Basic Chemistry", "States of Matter", "Atomic Structure",
                    "Thermodynamics", "Equilibrium", "Redox", "Organic Basics"
                ),
                "Class 12 Chemistry" to listOf(
                    "Solid State", "Solutions", "Electrochemistry", "Kinetics",
                    "Surface Chemistry", "p d f Blocks", "Coordination",
                    "Haloalkanes", "Alcohols Aldehydes Acids", "Amines", "Biomolecules"
                )
            ),
            "Physics" to mapOf(
                "Class 11 Physics" to listOf(
                    "Motion", "Laws of Motion", "Work Energy", "Rotation",
                    "Gravitation", "Solids and Fluids", "Thermal Physics", "Oscillations", "Waves"
                ),
                "Class 12 Physics" to listOf(
                    "Electrostatics", "Current Electricity", "Magnetism",
                    "EMI", "AC", "EM Waves", "Optics", "Modern Physics", "Semiconductors"
                )
            )
        ),

        // SSC CGL 2026 — Tier 1 (qualifying) + Tier 2 Paper 1 (final merit) syllabus.
        // Tier 2 Paper 2 (Statistics) is JSO-specific; Paper 3 (Finance & Economics) is AAO-specific —
        // both are folded in as subjects so students targeting those posts can track them separately.
        "SSC CGL" to mapOf(
            "Quantitative Aptitude" to mapOf(
                "Number System" to listOf(
                    "LCM and HCF", "Simplification", "Number Series",
                    "Surds and Indices", "Divisibility Rules", "Decimal and Fractions"
                ),
                "Arithmetic" to listOf(
                    "Percentage", "Profit and Loss", "Simple Interest", "Compound Interest",
                    "Ratio and Proportion", "Average", "Time and Work",
                    "Time Speed and Distance", "Partnership", "Mixture and Alligation",
                    "Boats and Streams", "Pipes and Cisterns"
                ),
                "Algebra" to listOf(
                    "Linear Equations", "Quadratic Equations", "Algebraic Identities", "Polynomials"
                ),
                "Geometry" to listOf(
                    "Triangles", "Circles and Chords and Tangents", "Quadrilaterals",
                    "Lines and Angles", "Congruence and Similarity", "Coordinate Geometry Basics"
                ),
                "Trigonometry" to listOf(
                    "Trigonometric Ratios", "Heights and Distances", "Trigonometric Identities",
                    "Maxima and Minima in Trigonometry"
                ),
                "Mensuration" to listOf(
                    "2D Mensuration — Area and Perimeter", "3D Mensuration — Volume and Surface Area",
                    "Mensuration of Combined Solids"
                ),
                "Data Interpretation" to listOf(
                    "Bar Graphs", "Pie Charts", "Line Graphs", "Tabulation", "Caselet DI"
                )
            ),
            "General Intelligence & Reasoning" to mapOf(
                "Verbal Reasoning" to listOf(
                    "Analogies", "Classification", "Coding-Decoding", "Blood Relations",
                    "Direction Sense", "Series Completion", "Syllogism",
                    "Statement and Conclusion", "Word Formation", "Alphabet and Number Test"
                ),
                "Non-Verbal Reasoning" to listOf(
                    "Mirror Images", "Water Images", "Paper Folding and Cutting",
                    "Embedded Figures", "Figure Series", "Pattern Completion", "Figure Counting"
                ),
                "Analytical Reasoning" to listOf(
                    "Puzzles", "Seating Arrangement", "Logical Venn Diagrams",
                    "Matrix Based Reasoning", "Input-Output", "Critical Thinking"
                )
            ),
            "English Language" to mapOf(
                "Grammar" to listOf(
                    "Spotting Errors", "Sentence Improvement", "Active and Passive Voice",
                    "Direct and Indirect Speech", "Parts of Speech", "Tenses",
                    "Subject-Verb Agreement"
                ),
                "Vocabulary" to listOf(
                    "Synonyms and Antonyms", "One Word Substitution",
                    "Idioms and Phrases", "Spelling Correction", "Homonyms"
                ),
                "Reading Comprehension" to listOf(
                    "Passage Based Questions", "Cloze Test", "Para Jumbles",
                    "Fill in the Blanks", "Sentence Rearrangement"
                )
            ),
            "General Awareness" to mapOf(
                "Static GK" to listOf(
                    "Indian History", "Indian Geography", "Indian Polity and Constitution",
                    "Indian Economy", "Books Awards and Important Days", "Art and Culture"
                ),
                "Current Affairs" to listOf(
                    "National and International Current Affairs", "Government Schemes",
                    "Sports", "Awards and Honours", "Appointments and Obituaries"
                ),
                "Science" to listOf(
                    "Physics Basics", "Chemistry Basics", "Biology Basics",
                    "Science and Technology Current Developments"
                )
            ),
            "Computer Knowledge" to mapOf(
                "Computer Fundamentals" to listOf(
                    "Computer Basics and Generations", "MS Office — Word Excel PowerPoint",
                    "Internet and Networking Basics", "Computer Security and Viruses",
                    "Operating Systems Basics"
                )
            ),
            "Statistics (JSO Paper 2)" to mapOf(
                "Descriptive Statistics" to listOf(
                    "Collection and Classification of Data", "Tabulation and Presentation",
                    "Measures of Central Tendency", "Measures of Dispersion",
                    "Correlation and Regression"
                ),
                "Probability and Sampling" to listOf(
                    "Probability Theory", "Random Variables and Distributions",
                    "Sampling Theory", "Statistical Inference"
                )
            ),
            "Finance & Economics (AAO Paper 3)" to mapOf(
                "Finance" to listOf(
                    "Financial Accounting", "Basic Concepts of Accounting",
                    "Self-Balancing Ledgers", "Depreciation Accounting"
                ),
                "Economics and Governance" to listOf(
                    "Comptroller and Auditor General of India", "Finance Commission",
                    "Theory of Demand and Supply", "Indian Economy Basics", "Government Budgeting"
                )
            )
        )
    )

    fun getTopicsForExam(exam: String): List<String> {
        return data[exam]?.values?.flatMap { it.values.flatten() } ?: emptyList()
    }

    fun getChaptersForSubject(exam: String, subject: String): List<String> {
        return data[exam]?.get(subject)?.keys?.toList() ?: emptyList()
    }

    fun getTopicsForChapter(exam: String, subject: String, chapter: String): List<String> {
        return data[exam]?.get(subject)?.get(chapter) ?: emptyList()
    }

    fun getAllSubjectsForExam(exam: String): List<String> {
        return data[exam]?.keys?.toList() ?: emptyList()
    }
}
